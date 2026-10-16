package net.ithandsfree.softphone.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class SoftphoneAccount(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    /** User Manager username used for BFF login */
    val umUsername: String,
    /** Stored locally (encrypted prefs). Prefer token refresh later. */
    val umPassword: String,
    /** Primary SMS DID for this account (digits, e.g. 15555550100) */
    val did: String = "",
    /** Optional SIP credentials for PJSIP / PJSUA2 registration */
    val sipExtension: String = "",
    val sipPassword: String = "",
    val sipDomain: String = "",
    val apiBaseUrl: String = "",
    /** BFF capability flags (entitlements). Defaults keep older installs working. */
    val capVoice: Boolean = true,
    val capSms: Boolean = true,
    val capMms: Boolean = true,
    val capDnd: Boolean = false,
    /** True only when the owner renamed the line in the app. Otherwise the line shows its extension. */
    val customLabel: Boolean = false,
) {
    /**
     * Name shown for the line: its extension ("1002"), which is what the person knows it by and stays right when an
     * extension changes hands, unless the owner gave it a custom name. Same rule as the desktop app.
     */
    val displayName: String
        get() = if (customLabel && label.isNotBlank()) label else sipExtension.ifBlank { label }
}

@Serializable
data class BrandingInfo(
    @SerialName("default_skin") val defaultSkin: String? = null,
    @SerialName("brand_mark") val brandMark: String? = null,
)

@Serializable
data class HealthResponse(
    val ok: Boolean = false,
    val service: String? = null,
    val branding: BrandingInfo? = null,
)

/** Public POST /v1/request-enrol — always generic check-inbox UX. */
@Serializable
data class RequestEnrolResponse(
    val ok: Boolean = false,
    val status: String? = null,
    @SerialName("retry_after") val retryAfter: Int = 30,
    val error: String? = null,
)

@Serializable
data class LoginResponse(
    val token: String,
    @SerialName("expires_in") val expiresIn: Int = 86400,
    val user: LoginUser? = null,
    val entitlements: EntitlementsInfo? = null,
    val lines: List<LineInfo> = emptyList(),
)

@Serializable
data class LoginUser(
    val id: Int? = null,
    val username: String? = null,
    val displayname: String? = null,
)

@Serializable
data class EntitlementsInfo(
    @SerialName("max_extensions") val maxExtensions: Int = 2,
)

@Serializable
data class LinesResponse(
    val lines: List<LineInfo> = emptyList(),
    val entitlements: EntitlementsInfo? = null,
)

@Serializable
data class LineCapabilities(
    val voice: Boolean = true,
    val sms: Boolean = true,
    val mms: Boolean = true,
    val dnd: Boolean = false,
)

@Serializable
data class LineInfo(
    val id: String? = null,
    val did: String,
    val e164: String? = null,
    val extension: String? = null,
    val capabilities: LineCapabilities? = null,
)

@Serializable
data class ThreadsResponse(
    /** BFF emits a JSON number (conversation count). Was wrongly typed as String. */
    val total: Int? = null,
    /** Sum of per-thread inbound unread (FreePBX sms_messages.read=0). */
    @SerialName("total_unread") val totalUnread: Int? = null,
    val threads: List<ThreadInfo> = emptyList(),
)

@Serializable
data class ThreadInfo(
    @SerialName("thread_id")
    @Serializable(with = JsonAsStringSerializer::class)
    val threadId: String? = null,
    val peer: String,
    @SerialName("local_did") val localDid: String? = null,
    @SerialName("last_message_at")
    @Serializable(with = JsonAsStringSerializer::class)
    val lastMessageAt: String? = null,
    @Serializable(with = JsonAsStringSerializer::class)
    val snippet: String? = null,
    /** "in" / "out" from FreePBX, used to prefix outbound previews with "You:". */
    @Serializable(with = JsonAsStringSerializer::class)
    val direction: String? = null,
    /** Inbound unread count for this conversation (0 when fully read). */
    val unread: Int = 0,
)

@Serializable
data class MessagesResponse(val messages: List<ChatMessage> = emptyList())

@Serializable
data class ChatMessage(
    val id: Int? = null,
    val emid: String? = null,
    val direction: String? = null,
    val from: String? = null,
    val to: String? = null,
    val body: String? = null,
    val timestamp: String? = null,
    val datetime: String? = null,
    val media: List<MediaRef> = emptyList(),
)

@Serializable
data class MediaRef(
    val name: String,
    val type: String? = null,
    val url: String? = null,
)

@Serializable
data class ApiError(
    val error: String? = null,
    val message: String? = null,
    val detail: String? = null,
)

@Serializable
data class MarkReadResponse(
    val ok: Boolean = false,
    val marked: Int = 0,
)

@Serializable
data class DeleteResponse(
    val ok: Boolean = false,
    val deleted: Int = 0,
    val error: String? = null,
)

/** SIP REGISTER material from BFF (authenticated HTTPS only). */
@Serializable
data class SipCredentialsResponse(
    val ok: Boolean = false,
    val extension: String? = null,
    val secret: String? = null,
    val tech: String? = null,
    val error: String? = null,
)

/** FreePBX Do Not Disturb state for a line. */
@Serializable
data class DndResponse(
    val ok: Boolean = false,
    val enabled: Boolean = false,
    val extension: String? = null,
    val error: String? = null,
)

/** PBX call history for one line (`GET /v1/lines/{did}/calls`). */
@Serializable
data class PbxCallsResponse(
    val extension: String? = null,
    val permissions: PbxCallPermissions = PbxCallPermissions(),
    val calls: List<PbxCall> = emptyList(),
)

@Serializable
data class PbxCallPermissions(val history: Boolean = false, val playback: Boolean = false, val download: Boolean = false)

@Serializable
data class PbxCall(
    val id: String = "",
    /** Unix seconds, call start. */
    val at: Long = 0,
    val direction: String = "",
    val peer: String = "",
    @SerialName("peer_name") val peerName: String = "",
    val disposition: String = "",
    val duration: Int = 0,
    val billsec: Int = 0,
    val recording: Boolean = false,
    val format: String? = null,
)

/** Voicemail for one line (`GET /v1/lines/{did}/voicemail`). */
@Serializable
data class VoicemailResponse(
    val extension: String? = null,
    val permissions: VoicemailPermissions = VoicemailPermissions(),
    /** The PBX's "My Voicemail" feature code (*97 by default); blank when the admin turned it off. */
    val dial: String = "",
    val new: Int = 0,
    val old: Int = 0,
    val messages: List<Voicemail> = emptyList(),
)

@Serializable
data class VoicemailPermissions(val voicemail: Boolean = false, val playback: Boolean = false, val download: Boolean = false)

@Serializable
data class VoicemailCount(val extension: String? = null, val new: Int = 0, val old: Int = 0)

@Serializable
data class Voicemail(
    val id: String = "",
    val folder: String = "",
    val new: Boolean = false,
    val urgent: Boolean = false,
    /** Unix seconds when the message was left. */
    val at: Long = 0,
    val number: String = "",
    val name: String = "",
    val duration: Int = 0,
)
