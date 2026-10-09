/* IHF Phone Windows voice bridge. GPL-2.0.
   PJSIP is compiled from source and linked into this library. */
#define WIN32_LEAN_AND_MEAN
#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>

#include "ihf_sip.h"

#include <stdio.h>
#include <string.h>

#include <pjsua-lib/pjsua.h>
#include <pjmedia/audiodev.h>
#include <pjmedia/null_port.h>
#include <pjmedia/tonegen.h>

/* 0.1.35: room for whole SIP header lines; the app drains this into its diagnostic log file. */
#define LOG_SLOTS 512
#define LOG_LEN 600

static CRITICAL_SECTION g_cs;
static CRITICAL_SECTION g_log_cs;
static int g_cs_ready;
static int g_started;
static pjsua_transport_id g_tls = -1;
static pjsua_transport_id g_tcp = -1;
static pjsua_acc_id g_acc = PJSUA_INVALID_ID;
static pjsua_acc_id g_lines[2] = { PJSUA_INVALID_ID, PJSUA_INVALID_ID };
static char g_ids[2][256];
static char g_regs[2][256];
static char g_users[2][128];
static char g_passes[2][128];
static int g_active_line;

static char g_ua[160];
static char g_ca[512];
static char g_id[256];
static char g_reg[256];
static char g_user[128];
static char g_pass[128];
static int g_capture = -1000;
static int g_playback = -1000;
static int g_muted;
static int g_held;
static int g_consult = -1;
/* 0.1.39 call waiting: the second call, ringing (waiting_state 1) or answered and now on hold (2). */
static int g_wait = -1;
static int g_cw_enabled = 1;
static pj_pool_t *g_cw_pool;
static pjmedia_port *g_cw_port;
static pjsua_conf_port_id g_cw_slot = PJSUA_INVALID_ID;
static void cw_beep(int on);
static void line_ext_for_acc(pjsua_acc_id acc, char *out, size_t n);
static float g_rx_gain = 1.0f;
static pjsua_player_id g_ring = PJSUA_INVALID_ID;
static pj_pool_t *g_meter_pool;
static pjmedia_port *g_meter_port;
static pjsua_conf_port_id g_meter_slot = PJSUA_INVALID_ID;

static ihf_status g_st;
static char g_logs[LOG_SLOTS][LOG_LEN];
static int g_log_read;
static int g_log_write;
static int g_log_count;

static void copy_str(char *dst, size_t n, const char *src) {
    size_t i;
    if (n == 0) return;
    if (!src) {
        dst[0] = 0;
        return;
    }
    for (i = 0; i + 1 < n && src[i]; ++i) dst[i] = src[i];
    dst[i] = 0;
}

static void copy_pj(char *dst, size_t n, const pj_str_t *src) {
    size_t len;
    if (n == 0) return;
    len = (src && src->ptr && src->slen > 0) ? (size_t)src->slen : 0;
    if (len >= n) len = n - 1;
    if (len > 0) memcpy(dst, src->ptr, len);
    dst[len] = 0;
}

static void push_log(const char *line) {
    if (!line || !line[0]) return;
    if (strstr(line, "password=")) return;
    /* Digest credentials never reach the log; the header name stays so a 401/407 round is still visible. */
    if (strstr(line, "Authorization:") || strstr(line, "authorization:")) {
        line = strstr(line, "Proxy-") ? "Proxy-Authorization: [removed]" : "Authorization: [removed]";
    }
    EnterCriticalSection(&g_log_cs);
    copy_str(g_logs[g_log_write], LOG_LEN, line);
    g_log_write = (g_log_write + 1) % LOG_SLOTS;
    if (g_log_count == LOG_SLOTS) g_log_read = (g_log_read + 1) % LOG_SLOTS;
    else g_log_count++;
    LeaveCriticalSection(&g_log_cs);
}

static void set_error(const char *msg) {
    EnterCriticalSection(&g_cs);
    copy_str(g_st.last_error, sizeof g_st.last_error, msg);
    LeaveCriticalSection(&g_cs);
    push_log(msg);
}

static void set_pj_error(const char *what, pj_status_t status) {
    char errbuf[160];
    char line[320];
    pj_strerror(status, errbuf, sizeof errbuf);
    snprintf(line, sizeof line, "%s: %s", what, errbuf);
    set_error(line);
}

/* A PJSIP log entry can be a whole SIP message: push it one line at a time so each header is kept (and redacted). */
static void on_log(int level, const char *data, int len) {
    char line[LOG_LEN];
    int start = 0;
    int i;
    (void)level;
    if (!data || len <= 0) return;
    for (i = 0; i <= len; ++i) {
        if (i == len || data[i] == '\n') {
            int n = i - start;
            if (n >= LOG_LEN) n = LOG_LEN - 1;
            if (n > 0) {
                memcpy(line, data + start, (size_t)n);
                line[n] = 0;
                while (n > 0 && (line[n - 1] == '\r' || line[n - 1] == ' ')) line[--n] = 0;
                if (n > 0) push_log(line);
            }
            start = i + 1;
        }
    }
}

/* The bridge only measures a port that has a listener. A silent port keeps the mic level moving without playing it back. */
static void attach_mic_meter(void) {
    pjsua_conf_port_info info;
    pj_status_t status;
    if (!g_started) return;
    if (pjsua_conf_get_port_info(0, &info) != PJ_SUCCESS) return;
    if (g_meter_slot == PJSUA_INVALID_ID) {
        g_meter_pool = pjsua_pool_create("ihf-meter", 512, 512);
        if (!g_meter_pool) return;
        status = pjmedia_null_port_create(
            g_meter_pool,
            info.clock_rate,
            info.channel_count,
            info.samples_per_frame,
            info.bits_per_sample,
            &g_meter_port);
        if (status != PJ_SUCCESS) return;
        status = pjsua_conf_add_port(g_meter_pool, g_meter_port, &g_meter_slot);
        if (status != PJ_SUCCESS) {
            g_meter_slot = PJSUA_INVALID_ID;
            return;
        }
    }
    pjsua_conf_connect(0, g_meter_slot);
}

static void ensure_thread(void) {
    static __declspec(thread) pj_thread_desc desc;
    static __declspec(thread) pj_thread_t *thr;
    if (!g_started) return;
    if (pj_thread_is_registered()) return;
    pj_bzero(desc, sizeof desc);
    pj_thread_register("ihf", desc, &thr);
}

static void on_reg_state2(pjsua_acc_id acc, pjsua_reg_info *info) {
    int code = 0;
    int active = 0;
    char reason[128];
    pjsua_acc_info ai;
    reason[0] = 0;
    if (info && info->cbparam) {
        code = info->cbparam->code;
        copy_pj(reason, sizeof reason, &info->cbparam->reason);
    }
    if (pjsua_acc_get_info(acc, &ai) == PJ_SUCCESS) {
        if (!code) code = (int)ai.status;
        if (!reason[0]) copy_pj(reason, sizeof reason, &ai.status_text);
        active = ai.has_registration && (ai.status / 100 == 2);
    }
    EnterCriticalSection(&g_cs);
    g_st.reg_code = code;
    copy_str(g_st.reg_reason, sizeof g_st.reg_reason, reason);
    if (acc == g_lines[1]) g_st.reg_b = active;
    else g_st.reg_active = active;
    LeaveCriticalSection(&g_cs);
    {
        char line[200];
        snprintf(line, sizeof line, "REGISTER %d %s", code, reason);
        push_log(line);
    }
}

static void on_incoming_call(pjsua_acc_id acc, pjsua_call_id call_id, pjsip_rx_data *rdata) {
    int busy;
    pjsua_call_info ci;
    char remote[256];
    (void)rdata;
    remote[0] = 0;
    EnterCriticalSection(&g_cs);
    busy = g_st.call_active;
    LeaveCriticalSection(&g_cs);
    if (pjsua_call_get_info(call_id, &ci) == PJ_SUCCESS) copy_pj(remote, sizeof remote, &ci.remote_info);
    if (busy && g_cw_enabled && g_wait < 0 && g_consult < 0) {
        /* Call waiting: keep it ringing (180) beside the current call; the app offers Hold & answer / Decline. */
        pjsua_call_answer(call_id, 180, NULL, NULL);
        EnterCriticalSection(&g_cs);
        g_wait = (int)call_id;
        g_st.waiting_state = 1;
        g_st.waiting_id = (int)call_id;
        copy_str(g_st.waiting_remote, sizeof g_st.waiting_remote, remote);
        line_ext_for_acc(acc, g_st.waiting_ext, sizeof g_st.waiting_ext);
        LeaveCriticalSection(&g_cs);
        cw_beep(1);
        push_log("call waiting");
        return;
    }
    if (busy || g_consult >= 0) {
        pjsua_call_answer(call_id, 486, NULL, NULL);
        push_log("second call rejected");
        return;
    }
    /* 180 keeps the INVITE up while the window waits. Answer sends 200. */
    pjsua_call_answer(call_id, 180, NULL, NULL);
    EnterCriticalSection(&g_cs);
    g_st.call_active = 1;
    g_st.incoming = 1;
    g_st.call_id = (int)call_id;
    copy_str(g_st.call_state, sizeof g_st.call_state, "Incoming");
    copy_str(g_st.remote, sizeof g_st.remote, remote);
    if (acc == g_lines[1]) copy_str(g_st.call_ext, sizeof g_st.call_ext, g_users[1]);
    else copy_str(g_st.call_ext, sizeof g_st.call_ext, g_users[0]);
    LeaveCriticalSection(&g_cs);
    push_log("incoming call");
}

static void on_call_media_state(pjsua_call_id call_id) {
    pjsua_call_info ci;
    unsigned i;
    if (pjsua_call_get_info(call_id, &ci) != PJ_SUCCESS) return;
    for (i = 0; i < ci.media_cnt; ++i) {
        if (ci.media[i].type != PJMEDIA_TYPE_AUDIO) continue;
        if (ci.media[i].status != PJSUA_CALL_MEDIA_ACTIVE &&
            ci.media[i].status != PJSUA_CALL_MEDIA_REMOTE_HOLD) {
            continue;
        }
        pjsua_conf_connect(ci.media[i].stream.aud.conf_slot, 0);
        pjsua_conf_connect(0, ci.media[i].stream.aud.conf_slot);
        /* Call volume (Settings): boosts what the other person says before it reaches the speaker. */
        pjsua_conf_adjust_rx_level(ci.media[i].stream.aud.conf_slot, g_rx_gain);
    }
    {
        int cap = 0, play = 0;
        char line[160];
        if (pjsua_get_snd_dev(&cap, &play) == PJ_SUCCESS) {
            snprintf(line, sizeof line, "media up: call %d capture %d playback %d volume %d%%",
                     (int)call_id, cap, play, (int)(g_rx_gain * 100.0f + 0.5f));
            push_log(line);
        }
    }
    if (g_muted) pjsua_conf_adjust_rx_level(0, 0.0f);
}

static void clear_consult_locked(void) {
    g_consult = -1;
    g_st.consult_active = 0;
    g_st.consult_id = -1;
    g_st.consult_state[0] = 0;
    g_st.consult_remote[0] = 0;
}

static void clear_waiting_locked(void) {
    g_wait = -1;
    g_st.waiting_state = 0;
    g_st.waiting_id = -1;
    g_st.waiting_remote[0] = 0;
    g_st.waiting_ext[0] = 0;
}

/* Call-waiting beep into the headset only (never into the call): two short 440 Hz tones every ~4.5 s. */
static void cw_beep(int on) {
    if (!g_started) return;
    if (on) {
        pjmedia_tone_desc tones[2];
        if (g_cw_slot == PJSUA_INVALID_ID) {
            pjsua_conf_port_info info;
            if (pjsua_conf_get_port_info(0, &info) != PJ_SUCCESS) return;
            g_cw_pool = pjsua_pool_create("ihf-cw", 512, 512);
            if (!g_cw_pool) return;
            if (pjmedia_tonegen_create(g_cw_pool, info.clock_rate, info.channel_count, info.samples_per_frame,
                                       16, 0, &g_cw_port) != PJ_SUCCESS) return;
            if (pjsua_conf_add_port(g_cw_pool, g_cw_port, &g_cw_slot) != PJ_SUCCESS) {
                g_cw_slot = PJSUA_INVALID_ID;
                return;
            }
        }
        pj_bzero(tones, sizeof tones);
        tones[0].freq1 = 440;
        tones[0].on_msec = 220;
        tones[0].off_msec = 160;
        tones[1].freq1 = 440;
        tones[1].on_msec = 220;
        tones[1].off_msec = 4000;
        pjmedia_tonegen_play(g_cw_port, 2, tones, PJMEDIA_TONEGEN_LOOP);
        pjsua_conf_connect(g_cw_slot, 0);
    } else if (g_cw_slot != PJSUA_INVALID_ID) {
        pjmedia_tonegen_stop(g_cw_port);
        pjsua_conf_disconnect(g_cw_slot, 0);
    }
}

static void line_ext_for_acc(pjsua_acc_id acc, char *out, size_t n) {
    if (acc == g_lines[1]) copy_str(out, n, g_users[1]);
    else copy_str(out, n, g_users[0]);
}

static void clear_call(void) {
    g_st.call_active = 0;
    g_st.incoming = 0;
    g_st.call_id = -1;
    g_st.held = 0;
    g_held = 0;
}

static void on_call_state(pjsua_call_id call_id, pjsip_event *e) {
    pjsua_call_info ci;
    char state[64];
    char remote[256];
    (void)e;
    if (pjsua_call_get_info(call_id, &ci) != PJ_SUCCESS) return;
    copy_pj(state, sizeof state, &ci.state_text);
    copy_pj(remote, sizeof remote, &ci.remote_info);
    EnterCriticalSection(&g_cs);
    if ((int)call_id == g_consult) {
        copy_str(g_st.consult_state, sizeof g_st.consult_state, state);
        if (remote[0]) copy_str(g_st.consult_remote, sizeof g_st.consult_remote, remote);
        if (ci.state == PJSIP_INV_STATE_DISCONNECTED) clear_consult_locked();
        else {
            g_st.consult_active = 1;
            g_st.consult_id = (int)call_id;
        }
        LeaveCriticalSection(&g_cs);
        return;
    }
    if ((int)call_id == g_wait) {
        /* The second call (ringing, or the call on hold) ended: back to one call. */
        int was_ringing = g_st.waiting_state == 1;
        if (ci.state == PJSIP_INV_STATE_DISCONNECTED) clear_waiting_locked();
        LeaveCriticalSection(&g_cs);
        if (ci.state == PJSIP_INV_STATE_DISCONNECTED) {
            char why[160];
            if (was_ringing) cw_beep(0);
            snprintf(why, sizeof why, "%s call %d ended: %d %.*s", was_ringing ? "waiting" : "held", (int)call_id,
                     ci.last_status, (int)ci.last_status_text.slen, ci.last_status_text.ptr ? ci.last_status_text.ptr : "");
            push_log(why);
        }
        return;
    }
    if (g_st.call_id < 0 || g_st.call_id == (int)call_id) {
        copy_str(g_st.call_state, sizeof g_st.call_state, state);
        if (remote[0]) copy_str(g_st.remote, sizeof g_st.remote, remote);
        if (ci.state == PJSIP_INV_STATE_DISCONNECTED) {
            int other = g_consult;
            static char dump[6000];
            char why[160];
            int promoted = 0;
            int promoted_ringing = 0;
            clear_call();
            clear_consult_locked();
            g_st.muted = 0;
            if (g_wait >= 0) {
                /* Two calls were up: the other one becomes the current call. A ringing second call turns into a
                   normal incoming call (the app rings for it); the call on hold stays on hold until Resume. */
                promoted = g_wait;
                promoted_ringing = g_st.waiting_state == 1;
                g_st.call_id = g_wait;
                g_st.call_active = 1;
                g_st.incoming = promoted_ringing;
                copy_str(g_st.remote, sizeof g_st.remote, g_st.waiting_remote);
                copy_str(g_st.call_ext, sizeof g_st.call_ext, g_st.waiting_ext);
                copy_str(g_st.call_state, sizeof g_st.call_state, promoted_ringing ? "Incoming" : "CONFIRMED");
                g_st.held = promoted_ringing ? 0 : 1;
                g_held = g_st.held;
                clear_waiting_locked();
            } else {
                promoted = -1;
            }
            LeaveCriticalSection(&g_cs);
            if (promoted >= 0 && promoted_ringing) cw_beep(0);
            /* A call never starts muted because the last one ended muted. */
            if (g_muted) {
                g_muted = 0;
                pjsua_conf_adjust_rx_level(0, 1.0f);
            }
            snprintf(why, sizeof why, "call %d ended: %d %.*s", (int)call_id, ci.last_status,
                     (int)ci.last_status_text.slen, ci.last_status_text.ptr ? ci.last_status_text.ptr : "");
            push_log(why);
            /* Jitter, loss and codec for the diagnostic log (no audio content). */
            if (pjsua_call_dump(call_id, PJ_TRUE, dump, sizeof dump, "  ") == PJ_SUCCESS) on_log(4, dump, (int)strlen(dump));
            if (other >= 0) pjsua_call_hangup(other, 0, NULL, NULL);
            return;
        }
        g_st.call_active = 1;
        g_st.call_id = (int)call_id;
        if (ci.state == PJSIP_INV_STATE_CONFIRMED) g_st.incoming = 0;
    }
    LeaveCriticalSection(&g_cs);
    push_log(state);
}

/* NOTIFY progress for a blind transfer (REFER). The PBX ends our leg itself after a 2xx. */
static void on_call_transfer_status(pjsua_call_id call_id, int st_code, const pj_str_t *st_text,
                                    pj_bool_t final, pj_bool_t *p_cont) {
    char text[96];
    char line[200];
    (void)p_cont;
    copy_pj(text, sizeof text, st_text);
    EnterCriticalSection(&g_cs);
    g_st.xfer_code = st_code;
    g_st.xfer_final = final ? 1 : 0;
    copy_str(g_st.xfer_text, sizeof g_st.xfer_text, text);
    LeaveCriticalSection(&g_cs);
    snprintf(line, sizeof line, "transfer from call %d: %d %s%s", (int)call_id, st_code, text, final ? " (final)" : "");
    push_log(line);
    /* RFC 5589: after a final 2xx NOTIFY the transferor ends its own dialog. Asterisk does not always send BYE
       (seen 2026-10-08 on a call-waiting call), which left the transferred call showing as active. */
    if (final && st_code / 100 == 2) {
        if (p_cont) *p_cont = PJ_FALSE;
        pjsua_call_hangup(call_id, 0, NULL, NULL);
        push_log("transfer complete; our leg hung up");
    }
}

static int create_transport(pjsip_transport_type_e type, const char *ca_file, pjsua_transport_id *out) {
    pjsua_transport_config cfg;
    pj_status_t status;
    pjsua_transport_config_default(&cfg);
    cfg.port = 0;
    if (type == PJSIP_TRANSPORT_TLS && ca_file && ca_file[0]) {
        copy_str(g_ca, sizeof g_ca, ca_file);
        cfg.tls_setting.ca_list_file = pj_str(g_ca);
        cfg.tls_setting.verify_server = PJ_TRUE;
        cfg.tls_setting.timeout.sec = 8;
        cfg.tls_setting.timeout.msec = 0;
    }
    status = pjsua_transport_create(type, &cfg, out);
    if (status != PJ_SUCCESS) {
        *out = -1;
        return -1;
    }
    return 0;
}

int ihf_sip_start(const char *user_agent, const char *ca_file) {
    pj_status_t status;
    pjsua_config cfg;
    pjsua_logging_config log_cfg;
    unsigned devs = 32;
    pjmedia_aud_dev_info devinfo[32];

    if (!g_cs_ready) {
        InitializeCriticalSection(&g_cs);
        InitializeCriticalSection(&g_log_cs);
        g_cs_ready = 1;
        g_st.call_id = -1;
    }
    if (g_started) return 0;

    status = pjsua_create();
    if (status != PJ_SUCCESS) {
        set_pj_error("pjsua_create", status);
        return -1;
    }

    pjsua_config_default(&cfg);
    pjsua_logging_config_default(&log_cfg);
    cfg.max_calls = 4;
    cfg.cb.on_incoming_call = &on_incoming_call;
    cfg.cb.on_call_state = &on_call_state;
    cfg.cb.on_call_media_state = &on_call_media_state;
    cfg.cb.on_reg_state2 = &on_reg_state2;
    cfg.cb.on_call_transfer_status = &on_call_transfer_status;
    if (user_agent && user_agent[0]) {
        copy_str(g_ua, sizeof g_ua, user_agent);
        cfg.user_agent = pj_str(g_ua);
    }
    log_cfg.level = 4;
    log_cfg.console_level = 4;
    log_cfg.cb = &on_log;

    status = pjsua_init(&cfg, &log_cfg, NULL);
    if (status != PJ_SUCCESS) {
        set_pj_error("pjsua_init", status);
        pjsua_destroy();
        return -1;
    }

    g_tls = g_tcp = -1;
    if (create_transport(PJSIP_TRANSPORT_TLS, ca_file, &g_tls) != 0) {
        push_log("TLS transport unavailable");
    }
    if (create_transport(PJSIP_TRANSPORT_TCP, NULL, &g_tcp) != 0) {
        push_log("TCP transport unavailable");
    }
    if (g_tls < 0 && g_tcp < 0) {
        set_error("SIP needs TLS or TCP. UDP is not used.");
        pjsua_destroy();
        return -1;
    }

    status = pjsua_start();
    if (status != PJ_SUCCESS) {
        set_pj_error("pjsua_start", status);
        pjsua_destroy();
        return -1;
    }

    g_started = 1;
    ensure_thread();

    devs = 32;
    if (pjsua_enum_aud_devs(devinfo, &devs) != PJ_SUCCESS) devs = 0;
    EnterCriticalSection(&g_cs);
    g_st.started = 1;
    g_st.tls_up = g_tls >= 0;
    g_st.tcp_up = g_tcp >= 0;
    if (g_tls >= 0) g_st.transport = 1;
    else g_st.transport = 2;
    g_st.null_audio = 0;
    g_st.last_error[0] = 0;
    LeaveCriticalSection(&g_cs);

    if (devs > 0) {
        int cap = 0;
        int play = 0;
        if (pjsua_get_snd_dev(&cap, &play) == PJ_SUCCESS) {
            g_capture = cap;
            g_playback = play;
        }
    }
    if (devs == 0) {
        status = pjsua_set_null_snd_dev();
        EnterCriticalSection(&g_cs);
        g_st.null_audio = 1;
        LeaveCriticalSection(&g_cs);
        if (status != PJ_SUCCESS) set_pj_error("null sound device", status);
        else push_log("no sound card; call signalling only");
    } else {
        attach_mic_meter();
    }
    push_log("PJSIP started");
    return 0;
}

void ihf_sip_stop(void) {
    if (!g_cs_ready || !g_started) return;
    ensure_thread();
    if (g_ring != PJSUA_INVALID_ID) {
        pjsua_player_destroy(g_ring);
        g_ring = PJSUA_INVALID_ID;
    }
    if (g_st.call_id >= 0) pjsua_call_hangup(g_st.call_id, 0, NULL, NULL);
    if (g_consult >= 0) pjsua_call_hangup(g_consult, 0, NULL, NULL);
    if (g_acc != PJSUA_INVALID_ID) {
        pjsua_acc_del(g_acc);
        g_acc = PJSUA_INVALID_ID;
    }
    g_started = 0;
    g_muted = 0;
    g_held = 0;
    g_capture = -1000;
    g_playback = -1000;
    g_meter_slot = PJSUA_INVALID_ID;
    g_cw_slot = PJSUA_INVALID_ID;
    g_cw_pool = NULL;
    g_cw_port = NULL;
    g_wait = -1;
    g_meter_pool = NULL;
    g_meter_port = NULL;
    g_lines[0] = PJSUA_INVALID_ID;
    g_lines[1] = PJSUA_INVALID_ID;
    g_acc = PJSUA_INVALID_ID;
    pjsua_destroy();
    EnterCriticalSection(&g_cs);
    memset(&g_st, 0, sizeof g_st);
    g_st.call_id = -1;
    g_st.consult_id = -1;
    g_consult = -1;
    LeaveCriticalSection(&g_cs);
}

void ihf_sip_get_status(ihf_status *out) {
    if (!out) return;
    if (!g_cs_ready) {
        memset(out, 0, sizeof *out);
        out->call_id = -1;
        out->consult_id = -1;
        return;
    }
    EnterCriticalSection(&g_cs);
    *out = g_st;
    LeaveCriticalSection(&g_cs);
}

int ihf_sip_register(const char *id_uri, const char *reg_uri, const char *user,
                     const char *password, int signalling) {
    pjsua_acc_config acc;
    pj_status_t status;
    if (!g_started) {
        set_error("PJSIP is not started");
        return -1;
    }
    ensure_thread();
    if (!id_uri || !reg_uri || !user || !password || !user[0] || !password[0]) {
        set_error("SIP account is incomplete");
        return -1;
    }
    {
        int slot = -1;
        int i;
        for (i = 0; i < 2; ++i) {
            if (g_lines[i] != PJSUA_INVALID_ID && strcmp(g_users[i], user) == 0) slot = i;
        }
        if (slot < 0) {
            for (i = 0; i < 2; ++i) {
                if (g_lines[i] == PJSUA_INVALID_ID) {
                    slot = i;
                    break;
                }
            }
        }
        if (slot < 0) {
            set_error("Two lines are already on this phone");
            return -1;
        }
        if (g_lines[slot] != PJSUA_INVALID_ID) {
            pjsua_acc_del(g_lines[slot]);
            g_lines[slot] = PJSUA_INVALID_ID;
        }
        copy_str(g_ids[slot], sizeof g_ids[slot], id_uri);
        copy_str(g_regs[slot], sizeof g_regs[slot], reg_uri);
        copy_str(g_users[slot], sizeof g_users[slot], user);
        copy_str(g_passes[slot], sizeof g_passes[slot], password);
        pjsua_acc_config_default(&acc);
        acc.id = pj_str(g_ids[slot]);
        acc.reg_uri = pj_str(g_regs[slot]);
        acc.register_on_acc_add = PJ_TRUE;
        acc.reg_timeout = 300;
        acc.reg_delay_before_refresh = 60;
        acc.reg_retry_interval = 30;
        acc.reg_first_retry_interval = 5;
        acc.cred_count = 1;
        acc.cred_info[0].realm = pj_str("*");
        acc.cred_info[0].scheme = pj_str("digest");
        acc.cred_info[0].username = pj_str(g_users[slot]);
        acc.cred_info[0].data_type = PJSIP_CRED_DATA_PLAIN_PASSWD;
        acc.cred_info[0].data = pj_str(g_passes[slot]);
        acc.allow_contact_rewrite = PJ_TRUE;
        acc.allow_via_rewrite = PJ_TRUE;
        acc.allow_sdp_nat_rewrite = PJ_TRUE;
        acc.use_srtp = PJMEDIA_SRTP_OPTIONAL;
        if (signalling == 1) {
            if (g_tls < 0) {
                set_error("TLS transport is not available");
                return -1;
            }
            acc.transport_id = g_tls;
            acc.contact_uri_params = pj_str(";transport=tls");
            acc.srtp_secure_signaling = 1;
        } else if (signalling == 2) {
            if (g_tcp < 0) {
                set_error("TCP transport is not available");
                return -1;
            }
            acc.transport_id = g_tcp;
            acc.srtp_secure_signaling = 0;
        } else {
            set_error("UDP SIP is not used");
            return -1;
        }
        status = pjsua_acc_add(&acc, PJ_TRUE, &g_lines[slot]);
        if (status != PJ_SUCCESS) {
            set_pj_error("account add", status);
            g_lines[slot] = PJSUA_INVALID_ID;
            return -1;
        }
        g_acc = g_lines[slot];
        g_active_line = slot;
        EnterCriticalSection(&g_cs);
        g_st.active_line = slot;
        if (slot == 0) copy_str(g_st.ext_a, sizeof g_st.ext_a, user);
        else copy_str(g_st.ext_b, sizeof g_st.ext_b, user);
        LeaveCriticalSection(&g_cs);
        push_log("REGISTER sent");
        return 0;
    }
}

int ihf_sip_use_line(int index) {
    if (index < 0 || index > 1 || g_lines[index] == PJSUA_INVALID_ID) return -1;
    g_active_line = index;
    g_acc = g_lines[index];
    EnterCriticalSection(&g_cs);
    g_st.active_line = index;
    LeaveCriticalSection(&g_cs);
    return 0;
}

int ihf_sip_call(const char *uri) {
    char buf[320];
    pj_str_t dst;
    pjsua_call_id call_id = PJSUA_INVALID_ID;
    pj_status_t status;
    if (!g_started || g_acc == PJSUA_INVALID_ID) {
        set_error("SIP account is not registered yet");
        return -1;
    }
    ensure_thread();
    if (g_st.call_active) {
        set_error("Already in a call — hang up first");
        return -1;
    }
    if (!uri || !uri[0]) {
        set_error("Empty destination");
        return -1;
    }
    copy_str(buf, sizeof buf, uri);
    dst = pj_str(buf);
    status = pjsua_call_make_call(g_acc, &dst, 0, NULL, NULL, &call_id);
    if (status != PJ_SUCCESS) {
        set_pj_error("make call", status);
        return -1;
    }
    EnterCriticalSection(&g_cs);
    g_st.call_active = 1;
    g_st.incoming = 0;
    g_st.call_id = (int)call_id;
    copy_str(g_st.call_state, sizeof g_st.call_state, "Calling");
    copy_str(g_st.remote, sizeof g_st.remote, uri);
    copy_str(g_st.call_ext, sizeof g_st.call_ext, g_users[g_active_line]);
    LeaveCriticalSection(&g_cs);
    LeaveCriticalSection(&g_cs);
    return 0;
}

int ihf_sip_answer(void) {
    pj_status_t status;
    int call_id;
    if (!g_started) return -1;
    ensure_thread();
    EnterCriticalSection(&g_cs);
    call_id = g_st.call_id;
    LeaveCriticalSection(&g_cs);
    if (call_id < 0) {
        set_error("No incoming call");
        return -1;
    }
    status = pjsua_call_answer(call_id, 200, NULL, NULL);
    if (status != PJ_SUCCESS) {
        set_pj_error("answer", status);
        return -1;
    }
    EnterCriticalSection(&g_cs);
    g_st.incoming = 0;
    copy_str(g_st.call_state, sizeof g_st.call_state, "Connected");
    LeaveCriticalSection(&g_cs);
    return 0;
}

int ihf_sip_decline(void) {
    pj_status_t status;
    int call_id;
    if (!g_started) return -1;
    ensure_thread();
    EnterCriticalSection(&g_cs);
    call_id = g_st.call_id;
    LeaveCriticalSection(&g_cs);
    if (call_id < 0) {
        set_error("No call to decline");
        return -1;
    }
    status = pjsua_call_hangup(call_id, 603, NULL, NULL);
    if (status != PJ_SUCCESS) {
        set_pj_error("decline", status);
        return -1;
    }
    return 0;
}

int ihf_sip_hangup(void) {
    pj_status_t status;
    int call_id;
    if (!g_started) return -1;
    ensure_thread();
    EnterCriticalSection(&g_cs);
    call_id = g_st.call_id;
    LeaveCriticalSection(&g_cs);
    if (call_id < 0) {
        set_error("No call");
        return -1;
    }
    status = pjsua_call_hangup(call_id, 603, NULL, NULL);
    if (status != PJ_SUCCESS) {
        set_pj_error("hangup", status);
        return -1;
    }
    return 0;
}

int ihf_sip_poll_log(char *buf, int buflen) {
    if (!buf || buflen <= 0 || !g_cs_ready) return 0;
    EnterCriticalSection(&g_log_cs);
    if (g_log_count == 0) {
        LeaveCriticalSection(&g_log_cs);
        return 0;
    }
    copy_str(buf, (size_t)buflen, g_logs[g_log_read]);
    g_log_read = (g_log_read + 1) % LOG_SLOTS;
    g_log_count--;
    LeaveCriticalSection(&g_log_cs);
    return 1;
}

int ihf_sip_aud_list(ihf_aud_dev *out, int max_count) {
    unsigned count = 32;
    pjmedia_aud_dev_info info[32];
    unsigned i;
    unsigned n;
    if (!out || max_count <= 0 || !g_started) return 0;
    ensure_thread();
    if (pjsua_enum_aud_devs(info, &count) != PJ_SUCCESS) return 0;
    n = count;
    if ((int)n > max_count) n = (unsigned)max_count;
    for (i = 0; i < n; ++i) {
        out[i].index = (int)i;
        out[i].inputs = (int)info[i].input_count;
        out[i].outputs = (int)info[i].output_count;
        copy_str(out[i].name, sizeof out[i].name, info[i].name);
    }
    return (int)n;
}

int ihf_sip_refresh_devices(void) {
    if (!g_started) return -1;
    ensure_thread();
    pjsua_set_null_snd_dev();
    pjmedia_aud_dev_refresh();
    return 0;
}

int ihf_sip_open_devices(int capture, int playback) {
    pj_status_t status;
    int old_capture;
    int old_playback;
    if (!g_started) return -1;
    ensure_thread();
    old_capture = g_capture;
    old_playback = g_playback;
    status = pjsua_set_snd_dev(capture, playback);
    if (status != PJ_SUCCESS) {
        set_pj_error("set sound device", status);
        if (old_capture != -1000 && old_playback != -1000 &&
            pjsua_set_snd_dev(old_capture, old_playback) == PJ_SUCCESS) {
            g_capture = old_capture;
            g_playback = old_playback;
            attach_mic_meter();
        }
        return -1;
    }
    g_capture = capture;
    g_playback = playback;
    EnterCriticalSection(&g_cs);
    g_st.null_audio = 0;
    LeaveCriticalSection(&g_cs);
    if (g_muted) pjsua_conf_adjust_rx_level(0, 0.0f);
    attach_mic_meter();
    return 0;
}

int ihf_sip_mic_level(void) {
    unsigned tx = 0;
    unsigned rx = 0;
    if (!g_started) return 0;
    ensure_thread();
    pjsua_conf_get_signal_level(0, &tx, &rx);
    if (rx > 255) rx = 255;
    return (int)rx;
}

static int switch_snd(int capture, int playback) {
    pj_status_t status;
    int old_capture;
    int old_playback;
    if (!g_started) return -1;
    ensure_thread();
    EnterCriticalSection(&g_cs);
    if (g_st.null_audio) {
        LeaveCriticalSection(&g_cs);
        set_error("No sound device");
        return -1;
    }
    LeaveCriticalSection(&g_cs);
    old_capture = g_capture;
    old_playback = g_playback;
    status = pjsua_set_snd_dev(capture, playback);
    if (status != PJ_SUCCESS) {
        set_pj_error("set sound device", status);
        if (old_capture != -1000 && old_playback != -1000 &&
            (old_capture != capture || old_playback != playback) &&
            pjsua_set_snd_dev(old_capture, old_playback) == PJ_SUCCESS) {
            g_capture = old_capture;
            g_playback = old_playback;
            if (g_muted) pjsua_conf_adjust_rx_level(0, 0.0f);
            attach_mic_meter();
        }
        return -1;
    }
    g_capture = capture;
    g_playback = playback;
    if (g_muted) pjsua_conf_adjust_rx_level(0, 0.0f);
    attach_mic_meter();
    return 0;
}

int ihf_sip_ring_stop(void) {
    pjsua_conf_port_id slot;
    if (!g_started || g_ring == PJSUA_INVALID_ID) return 0;
    ensure_thread();
    slot = pjsua_player_get_conf_port(g_ring);
    if (slot != PJSUA_INVALID_ID) pjsua_conf_disconnect(slot, 0);
    pjsua_player_destroy(g_ring);
    g_ring = PJSUA_INVALID_ID;
    return 0;
}

int ihf_sip_ring_start(const char *wav_path) {
    pj_str_t file;
    pjsua_player_id id = PJSUA_INVALID_ID;
    pjsua_conf_port_id slot;
    pj_status_t status;
    char path[512];
    if (!g_started || !wav_path || !wav_path[0]) return -1;
    ensure_thread();
    ihf_sip_ring_stop();
    copy_str(path, sizeof path, wav_path);
    file = pj_str(path);
    status = pjsua_player_create(&file, 0, &id);
    if (status != PJ_SUCCESS) {
        set_pj_error("ringtone", status);
        return -1;
    }
    slot = pjsua_player_get_conf_port(id);
    status = pjsua_conf_connect(slot, 0);
    if (status != PJ_SUCCESS) {
        pjsua_player_destroy(id);
        set_pj_error("ringtone", status);
        return -1;
    }
    g_ring = id;
    return 0;
}

int ihf_sip_set_capture(int index) {
    int playback = g_playback;
    if (playback == -1000) playback = PJMEDIA_AUD_DEFAULT_PLAYBACK_DEV;
    return switch_snd(index, playback);
}

int ihf_sip_set_playback(int index) {
    int capture = g_capture;
    if (capture == -1000) capture = PJMEDIA_AUD_DEFAULT_CAPTURE_DEV;
    return switch_snd(capture, index);
}

int ihf_sip_set_mute(int muted) {
    if (!g_started) return -1;
    ensure_thread();
    g_muted = muted ? 1 : 0;
    EnterCriticalSection(&g_cs);
    g_st.muted = g_muted;
    if (g_st.null_audio) {
        LeaveCriticalSection(&g_cs);
        return 0;
    }
    LeaveCriticalSection(&g_cs);
    /* Slot 0 is the sound device: its rx level is the microphone going into the call, its tx level is what the
       speaker or headset plays. Mute silences only the microphone (0.1.35 muted tx, which also silenced the caller). */
    if (pjsua_conf_adjust_rx_level(0, g_muted ? 0.0f : 1.0f) != PJ_SUCCESS) {
        set_error("Mute was not applied");
        return -1;
    }
    return 0;
}

static int active_call(void) {
    int call_id;
    if (!g_started) return -1;
    ensure_thread();
    EnterCriticalSection(&g_cs);
    call_id = g_st.call_id;
    LeaveCriticalSection(&g_cs);
    return call_id;
}

static void set_held(int held) {
    g_held = held ? 1 : 0;
    EnterCriticalSection(&g_cs);
    g_st.held = g_held;
    LeaveCriticalSection(&g_cs);
}

int ihf_sip_hold(void) {
    int call_id = active_call();
    pj_status_t status;
    if (call_id < 0) {
        set_error("No call to hold");
        return -1;
    }
    status = pjsua_call_set_hold(call_id, NULL);
    if (status != PJ_SUCCESS) {
        set_pj_error("hold", status);
        return -1;
    }
    set_held(1);
    return 0;
}

int ihf_sip_resume(void) {
    int call_id = active_call();
    pj_status_t status;
    if (call_id < 0) {
        set_error("No call to resume");
        return -1;
    }
    status = pjsua_call_reinvite(call_id, PJSUA_CALL_UNHOLD, NULL);
    if (status != PJ_SUCCESS) {
        set_pj_error("resume", status);
        return -1;
    }
    set_held(0);
    return 0;
}

int ihf_sip_transfer(const char *uri) {
    int call_id = active_call();
    pj_str_t dest;
    pj_status_t status;
    if (call_id < 0 || !uri || !uri[0]) {
        set_error("No call to transfer");
        return -1;
    }
    dest.ptr = (char *)uri;
    dest.slen = (pj_ssize_t)strlen(uri);
    EnterCriticalSection(&g_cs);
    g_st.xfer_code = 0;
    g_st.xfer_final = 0;
    g_st.xfer_text[0] = 0;
    LeaveCriticalSection(&g_cs);
    status = pjsua_call_xfer(call_id, &dest, NULL);
    if (status != PJ_SUCCESS) {
        set_pj_error("transfer", status);
        return -1;
    }
    return 0;
}

int ihf_sip_consult(const char *uri) {
    char buf[320];
    pj_str_t dst;
    pjsua_call_id call_id = PJSUA_INVALID_ID;
    pj_status_t status;
    int primary = active_call();
    if (primary < 0 || !uri || !uri[0]) {
        set_error("No call to transfer");
        return -1;
    }
    if (g_consult >= 0) {
        set_error("Already speaking to someone else");
        return -1;
    }
    if (g_wait >= 0) {
        set_error("End the other call first");
        return -1;
    }
    if (!g_held && ihf_sip_hold() != 0) return -1;
    copy_str(buf, sizeof buf, uri);
    dst = pj_str(buf);
    {
        /* Consult from the line the call is on, not the line chosen for new calls. */
        pjsua_call_info pci;
        pjsua_acc_id acc = g_acc;
        if (pjsua_call_get_info(primary, &pci) == PJ_SUCCESS && pjsua_acc_is_valid(pci.acc_id)) acc = pci.acc_id;
        status = pjsua_call_make_call(acc, &dst, 0, NULL, NULL, &call_id);
    }
    if (status != PJ_SUCCESS) {
        set_pj_error("consult", status);
        return -1;
    }
    EnterCriticalSection(&g_cs);
    g_consult = (int)call_id;
    g_st.consult_active = 1;
    g_st.consult_id = (int)call_id;
    copy_str(g_st.consult_state, sizeof g_st.consult_state, "Calling");
    copy_str(g_st.consult_remote, sizeof g_st.consult_remote, uri);
    LeaveCriticalSection(&g_cs);
    return 0;
}

int ihf_sip_consult_finish(void) {
    int primary = active_call();
    pj_status_t status;
    if (primary < 0 || g_consult < 0) {
        set_error("Speak to the other person first");
        return -1;
    }
    status = pjsua_call_xfer_replaces(primary, g_consult, 0, NULL);
    if (status != PJ_SUCCESS) {
        set_pj_error("attended transfer", status);
        return -1;
    }
    return 0;
}

int ihf_sip_consult_cancel(void) {
    int consult = g_consult;
    if (consult < 0) return 0;
    pjsua_call_hangup(consult, 0, NULL, NULL);
    EnterCriticalSection(&g_cs);
    clear_consult_locked();
    LeaveCriticalSection(&g_cs);
    if (g_held) ihf_sip_resume();
    return 0;
}

int ihf_sip_dtmf(const char *digits) {
    int call_id = active_call();
    pj_str_t tone;
    pj_status_t status;
    if (call_id < 0 || !digits || !digits[0]) return -1;
    tone.ptr = (char *)digits;
    tone.slen = (pj_ssize_t)strlen(digits);
    status = pjsua_call_dial_dtmf(call_id, &tone);
    if (status != PJ_SUCCESS) {
        set_pj_error("dtmf", status);
        return -1;
    }
    return 0;
}

int ihf_sip_set_ec(int enabled) {
    pj_status_t status;
    if (!g_started) return -1;
    ensure_thread();
    status = pjsua_set_ec(enabled ? PJSUA_DEFAULT_EC_TAIL_LEN : 0, 0);
    if (status != PJ_SUCCESS) {
        set_pj_error("echo canceller", status);
        return -1;
    }
    push_log(enabled ? "echo canceller on" : "echo canceller off");
    return 0;
}

/* Level of the other person's audio on the current call: tells "the caller is quiet" from "this PC plays quietly". */
int ihf_sip_rx_level(void) {
    unsigned tx = 0, rx = 0;
    int call_id = active_call();
    pjsua_conf_port_id slot;
    if (call_id < 0) return -1;
    slot = pjsua_call_get_conf_port(call_id);
    if (slot == PJSUA_INVALID_ID) return -1;
    if (pjsua_conf_get_signal_level(slot, &tx, &rx) != PJ_SUCCESS) return -1;
    return (int)rx;
}

int ihf_sip_set_rx_gain(int percent) {
    int call_id;
    pjsua_conf_port_id slot;
    char line[80];
    if (percent < 50) percent = 50;
    if (percent > 400) percent = 400;
    g_rx_gain = (float)percent / 100.0f;
    snprintf(line, sizeof line, "call volume %d%%", percent);
    push_log(line);
    call_id = active_call();
    if (call_id < 0) return 0;
    slot = pjsua_call_get_conf_port(call_id);
    if (slot != PJSUA_INVALID_ID) pjsua_conf_adjust_rx_level(slot, g_rx_gain);
    if (g_consult >= 0) {
        slot = pjsua_call_get_conf_port(g_consult);
        if (slot != PJSUA_INVALID_ID) pjsua_conf_adjust_rx_level(slot, g_rx_gain);
    }
    return 0;
}

/* ---- 0.1.39 call waiting ------------------------------------------------------------------------------------- */

int ihf_sip_set_call_waiting(int enabled) {
    g_cw_enabled = enabled ? 1 : 0;
    push_log(enabled ? "call waiting on" : "call waiting off");
    return 0;
}

/* The two calls trade places in the status: [now] becomes the current call, [other] the call on hold. */
static void swap_roles_locked(int now, int other, const char *now_state) {
    char remote[256];
    char ext[32];
    copy_str(remote, sizeof remote, g_st.remote);
    copy_str(ext, sizeof ext, g_st.call_ext);
    g_st.call_id = now;
    g_st.call_active = 1;
    g_st.incoming = 0;
    copy_str(g_st.remote, sizeof g_st.remote, g_st.waiting_remote);
    copy_str(g_st.call_ext, sizeof g_st.call_ext, g_st.waiting_ext);
    copy_str(g_st.call_state, sizeof g_st.call_state, now_state);
    g_st.held = 0;
    g_held = 0;
    g_wait = other;
    g_st.waiting_state = 2;
    g_st.waiting_id = other;
    copy_str(g_st.waiting_remote, sizeof g_st.waiting_remote, remote);
    copy_str(g_st.waiting_ext, sizeof g_st.waiting_ext, ext);
}

int ihf_sip_waiting_answer(void) {
    int current;
    int waiting;
    pj_status_t status;
    if (!g_started) return -1;
    ensure_thread();
    EnterCriticalSection(&g_cs);
    current = g_st.call_id;
    waiting = g_st.waiting_state == 1 ? g_wait : -1;
    LeaveCriticalSection(&g_cs);
    if (waiting < 0) {
        set_error("No call waiting");
        return -1;
    }
    if (current >= 0 && !g_held) {
        status = pjsua_call_set_hold(current, NULL);
        if (status != PJ_SUCCESS) {
            set_pj_error("hold before answer", status);
            return -1;
        }
    }
    cw_beep(0);
    /* Swap first, so the CONFIRMED callback for the answered call already finds it as the current call. */
    EnterCriticalSection(&g_cs);
    swap_roles_locked(waiting, current, "Connected");
    LeaveCriticalSection(&g_cs);
    status = pjsua_call_answer(waiting, 200, NULL, NULL);
    if (status != PJ_SUCCESS) {
        set_pj_error("answer waiting call", status);
        pjsua_call_hangup(waiting, 0, NULL, NULL);
        return -1;
    }
    push_log("call waiting answered; first call on hold");
    return 0;
}

int ihf_sip_waiting_end(void) {
    int waiting;
    int ringing;
    pj_status_t status;
    if (!g_started) return -1;
    ensure_thread();
    EnterCriticalSection(&g_cs);
    waiting = g_wait;
    ringing = g_st.waiting_state == 1;
    LeaveCriticalSection(&g_cs);
    if (waiting < 0) {
        set_error("No other call");
        return -1;
    }
    if (ringing) cw_beep(0);
    /* 486 lets the PBX carry on as for a busy line (other devices, then voicemail). */
    status = pjsua_call_hangup(waiting, ringing ? 486 : 0, NULL, NULL);
    if (status != PJ_SUCCESS) {
        set_pj_error(ringing ? "decline waiting call" : "end call on hold", status);
        return -1;
    }
    return 0;
}

int ihf_sip_swap(void) {
    int current;
    int other;
    pj_status_t status;
    if (!g_started) return -1;
    ensure_thread();
    EnterCriticalSection(&g_cs);
    current = g_st.call_id;
    other = g_st.waiting_state == 2 ? g_wait : -1;
    LeaveCriticalSection(&g_cs);
    if (other < 0) {
        set_error("No call on hold");
        return -1;
    }
    if (current >= 0 && !g_held) {
        status = pjsua_call_set_hold(current, NULL);
        if (status != PJ_SUCCESS) {
            set_pj_error("hold", status);
            return -1;
        }
    }
    status = pjsua_call_reinvite(other, PJSUA_CALL_UNHOLD, NULL);
    if (status != PJ_SUCCESS) {
        set_pj_error("resume", status);
        return -1;
    }
    EnterCriticalSection(&g_cs);
    swap_roles_locked(other, current, "CONFIRMED");
    LeaveCriticalSection(&g_cs);
    push_log("calls swapped");
    return 0;
}
