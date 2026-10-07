/* IHF Phone Windows voice bridge. GPL-2.0. See the LICENSE at the repo root.
   Links PJSIP, which is GPL-2.0 when built without the Teluu commercial license. */
#ifndef IHF_SIP_H
#define IHF_SIP_H

#ifdef _WIN32
#define IHF_API __declspec(dllexport)
#else
#define IHF_API
#endif

#ifdef __cplusplus
extern "C" {
#endif

typedef struct ihf_status {
    int started;
    int transport;   /* 0 none, 1 tls, 2 tcp, 3 udp */
    int tls_up;
    int tcp_up;
    int null_audio;
    int reg_active;
    int reg_code;
    int call_active;
    int incoming;
    int call_id;
    char reg_reason[128];
    char call_state[64];
    char remote[256];
    char last_error[512];
    int muted;
    int held;
    int consult_active;
    int consult_id;
    char consult_state[64];
    char consult_remote[256];
    int active_line;
    int reg_b;
    char ext_a[32];
    char ext_b[32];
    char call_ext[32];
} ihf_status;

typedef struct ihf_aud_dev {
    int index;
    int inputs;
    int outputs;
    char name[128];
} ihf_aud_dev;

IHF_API int ihf_sip_start(const char *user_agent, const char *ca_file);
IHF_API void ihf_sip_stop(void);
IHF_API void ihf_sip_get_status(ihf_status *out);
IHF_API int ihf_sip_register(const char *id_uri, const char *reg_uri,
                             const char *user, const char *password,
                             int signalling);
IHF_API int ihf_sip_use_line(int index);
IHF_API int ihf_sip_call(const char *uri);
IHF_API int ihf_sip_answer(void);
IHF_API int ihf_sip_decline(void);
IHF_API int ihf_sip_hangup(void);
IHF_API int ihf_sip_poll_log(char *buf, int buflen);
IHF_API int ihf_sip_aud_list(ihf_aud_dev *out, int max_count);
IHF_API int ihf_sip_refresh_devices(void);
IHF_API int ihf_sip_open_devices(int capture, int playback);
IHF_API int ihf_sip_mic_level(void);
IHF_API int ihf_sip_set_capture(int index);
IHF_API int ihf_sip_set_playback(int index);
IHF_API int ihf_sip_set_mute(int muted);
IHF_API int ihf_sip_hold(void);
IHF_API int ihf_sip_resume(void);
IHF_API int ihf_sip_transfer(const char *uri);
IHF_API int ihf_sip_consult(const char *uri);
IHF_API int ihf_sip_consult_finish(void);
IHF_API int ihf_sip_consult_cancel(void);
IHF_API int ihf_sip_dtmf(const char *digits);
IHF_API int ihf_sip_ring_start(const char *wav_path);
IHF_API int ihf_sip_ring_stop(void);

#ifdef __cplusplus
}
#endif

#endif
