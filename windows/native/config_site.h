/* Copied over pjlib/include/pj/config_site.h before the Windows build.
   SIP TLS and optional SRTP match the Android client. Video stays off.
   Null audio lets a Hyper-V guest with no sound card still negotiate a call. */
#define PJ_HAS_SSL_SOCK 1
#define PJMEDIA_HAS_SRTP 1
#define PJMEDIA_HAS_VIDEO 0
#define PJMEDIA_AUDIO_DEV_HAS_WMME 1
#define PJMEDIA_AUDIO_DEV_HAS_NULL_AUDIO 1
#define PJSUA_MAX_CALLS 4
