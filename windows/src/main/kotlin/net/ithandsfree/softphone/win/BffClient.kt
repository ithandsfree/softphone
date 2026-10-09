package net.ithandsfree.softphone.win

import java.io.ByteArrayOutputStream
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * HTTPS client for the existing softphone BFF. SMS goes through this API,
 * not SIP MESSAGE. GPL-2.0.
 */
class BffException(val error: String, val httpCode: Int) : Exception(error)

class BffClient(
    baseUrl: String,
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build(),
) {
    private val root = baseUrl.trim().trimEnd('/')
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    fun health(): Boolean = runCatching {
        val body = get("/v1/health", token = null)
        json.decodeFromString(HealthResponse.serializer(), body).ok
    }.getOrDefault(false)

    fun requestEnrol(email: String): RequestEnrolResponse {
        val payload = """{"email":${json.encodeToString(email.trim())}}"""
        val (code, text) = post("/v1/request-enrol", payload, token = null)
        val parsed = runCatching { json.decodeFromString(RequestEnrolResponse.serializer(), text) }.getOrNull()
        if (code !in 200..299) {
            throw BffException(parsed?.error ?: apiError(text) ?: "request_enrol_failed", code)
        }
        return parsed ?: RequestEnrolResponse(ok = true, status = "check_inbox")
    }

    /** User Manager sign-in. Returns a new bearer and the user's lines, the same shape as [session]. */
    fun login(username: String, password: String): LoginResponse {
        val payload = """{"username":${json.encodeToString(username.trim())},"password":${json.encodeToString(password)}}"""
        val (code, text) = post("/v1/login", payload, token = null)
        if (code !in 200..299) {
            throw BffException(apiError(text) ?: "login_failed", code)
        }
        val parsed = json.decodeFromString(LoginResponse.serializer(), text)
        if (parsed.token.isBlank()) throw BffException("login_returned_no_token", code)
        return parsed
    }

    /** Ends this device's session on the server (the token stops working). */
    fun logout(token: String) {
        post("/v1/logout", "{}", token)
    }

    fun session(token: String): LoginResponse {
        val text = get("/v1/session", token)
        val parsed = json.decodeFromString(LoginResponse.serializer(), text)
        return if (parsed.token.isBlank()) parsed.copy(token = token) else parsed
    }

    /** The user's lines with their capabilities and live DND. */
    fun lines(token: String): LinesResponse {
        val text = get("/v1/lines", token)
        return json.decodeFromString(LinesResponse.serializer(), text)
    }

    /** PBX call history for the line's extension (UCP Call History rules apply on the server). */
    fun calls(token: String, did: String, limit: Int = 100): CallsResponse {
        val text = get("/v1/lines/${enc(did)}/calls?limit=$limit", token)
        return json.decodeFromString(CallsResponse.serializer(), text)
    }

    /** One call recording's audio (WAV from the PBX). */
    fun recording(token: String, did: String, callId: String, download: Boolean = false): ByteArray =
        getBytes("/v1/lines/${enc(did)}/calls/${enc(callId)}/recording" + if (download) "?download=1" else "", token)

    /** Voicemail for the line's extension (UCP Voicemail permissions). New messages first. */
    fun voicemail(token: String, did: String, limit: Int = 100): VoicemailResponse {
        val text = get("/v1/lines/${enc(did)}/voicemail?limit=$limit", token)
        return json.decodeFromString(VoicemailResponse.serializer(), text)
    }

    /** New and heard counts, for badges. */
    fun voicemailCount(token: String, did: String): VoicemailCount {
        val text = get("/v1/lines/${enc(did)}/voicemail/count", token)
        return json.decodeFromString(VoicemailCount.serializer(), text)
    }

    /** One voicemail's audio, always PCM WAV. */
    fun voicemailAudio(token: String, did: String, id: String, download: Boolean = false): ByteArray =
        getBytes("/v1/lines/${enc(did)}/voicemail/${enc(id)}/audio" + if (download) "?download=1" else "", token)

    fun voicemailHeard(token: String, did: String, id: String) {
        val code = post("/v1/lines/${enc(did)}/voicemail/${enc(id)}/heard", "{}", token)
        if (code.first !in 200..299) throw BffException(apiError(code.second) ?: "voicemail_heard_failed", code.first)
    }

    fun deleteVoicemail(token: String, did: String, id: String) {
        val builder = HttpRequest.newBuilder(URI.create(root + "/v1/lines/${enc(did)}/voicemail/${enc(id)}"))
            .timeout(Duration.ofSeconds(30))
            .DELETE()
            .header(TOKEN_HEADER, token)
        val resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        // 404: the message is already gone (deleted from another device or a repeated press), which is what was asked.
        if (resp.statusCode() !in 200..299 && resp.statusCode() != 404) {
            throw BffException(apiError(resp.body()) ?: "voicemail_delete_failed", resp.statusCode())
        }
    }

    fun sipCredentials(token: String, did: String): SipCredentialsResponse {
        val text = get("/v1/lines/${enc(did)}/sip-credentials", token)
        return json.decodeFromString(SipCredentialsResponse.serializer(), text)
    }

    fun listThreads(token: String, did: String): ThreadsResponse {
        val text = get("/v1/lines/${enc(did)}/threads?limit=50", token)
        return json.decodeFromString(ThreadsResponse.serializer(), text)
    }

    fun listMessages(token: String, did: String, peer: String): MessagesResponse {
        val text = get("/v1/lines/${enc(did)}/threads/${enc(peer)}/messages", token)
        return json.decodeFromString(MessagesResponse.serializer(), text)
    }

    /** Picture attached to a message. The BFF requires the same bearer token as the thread. */
    fun mediaBytes(token: String, nameOrUrl: String): ByteArray {
        val name = when {
            nameOrUrl.startsWith("http://") || nameOrUrl.startsWith("https://") ->
                nameOrUrl.substringAfterLast('/').substringBefore('?')
            nameOrUrl.contains('/') -> nameOrUrl.substringAfterLast('/')
            else -> nameOrUrl
        }.trim()
        if (name.isBlank()) return byteArrayOf()
        return getBytes("/v1/media/${enc(name)}", token)
    }

    fun sendSms(token: String, did: String, to: String, body: String): SendSmsResponse {
        val payload = buildString {
            append('{')
            append("\"to\":").append(json.encodeToString(to))
            append(',')
            append("\"body\":").append(json.encodeToString(body))
            append('}')
        }
        val (code, text) = post("/v1/lines/${enc(did)}/messages", payload, token)
        val parsed = runCatching { json.decodeFromString(SendSmsResponse.serializer(), text) }.getOrNull()
        if (code !in 200..299 || parsed?.ok != true) {
            val detail = listOfNotNull(parsed?.error, parsed?.message, parsed?.detail, apiError(text))
                .joinToString(": ")
                .ifBlank { "send_failed" }
            throw BffException(detail, code)
        }
        return parsed
    }

    internal fun sendMedia(token: String, did: String, to: String, photo: MmsPhoto) {
        val boundary = "ihf${System.nanoTime()}"
        val body = ByteArrayOutputStream()
        fun write(text: String) = body.write(text.toByteArray(Charsets.UTF_8))
        val fileName = photo.name.filter { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }.ifBlank { "photo.jpg" }
        write("--$boundary\r\n")
        write("Content-Disposition: form-data; name=\"to\"\r\n\r\n")
        write(to.trim())
        write("\r\n--$boundary\r\n")
        write("Content-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\n")
        write("Content-Type: ${photo.type}\r\n\r\n")
        body.write(photo.bytes)
        write("\r\n--$boundary--\r\n")
        val request = HttpRequest.newBuilder(URI.create(root + "/v1/lines/${enc(did)}/messages/media"))
            .timeout(Duration.ofSeconds(60))
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .header(TOKEN_HEADER, token)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
            .build()
        val resp = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() !in 200..299) {
            throw BffException(apiError(resp.body()) ?: "send_media_failed", resp.statusCode())
        }
    }

    fun dnd(token: String, did: String): Boolean {
        val text = get("/v1/lines/${enc(did)}/dnd", token)
        return json.decodeFromString(DndResponse.serializer(), text).enabled
    }

    fun setDnd(token: String, did: String, enabled: Boolean) {
        val code = post("/v1/lines/${enc(did)}/dnd", """{"enabled":${if (enabled) "true" else "false"}}""", token)
        if (code.first !in 200..299) {
            throw BffException(apiError(code.second) ?: "dnd_set_failed", code.first)
        }
    }

    private fun get(path: String, token: String?): String {
        val builder = HttpRequest.newBuilder(URI.create(root + path))
            .timeout(Duration.ofSeconds(30))
            .GET()
        if (!token.isNullOrBlank()) builder.header(TOKEN_HEADER, token)
        val resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() !in 200..299) {
            throw BffException(apiError(resp.body()) ?: "http_${resp.statusCode()}", resp.statusCode())
        }
        return resp.body()
    }

    private fun getBytes(path: String, token: String): ByteArray {
        val builder = HttpRequest.newBuilder(URI.create(root + path))
            .timeout(Duration.ofSeconds(30))
            .GET()
            .header(TOKEN_HEADER, token)
        val resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
        if (resp.statusCode() !in 200..299) {
            throw BffException("media_failed", resp.statusCode())
        }
        return resp.body()
    }

    private fun post(path: String, payload: String, token: String?): Pair<Int, String> {
        val builder = HttpRequest.newBuilder(URI.create(root + path))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
        if (!token.isNullOrBlank()) builder.header(TOKEN_HEADER, token)
        val resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        return resp.statusCode() to resp.body()
    }

    private fun apiError(text: String): String? =
        runCatching { json.decodeFromString(ApiError.serializer(), text).error }.getOrNull()

    private fun enc(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8)

    companion object {
        const val TOKEN_HEADER = "X-IHF-Token"
    }
}

@Serializable
data class DndResponse(
    val ok: Boolean = false,
    val enabled: Boolean = false,
    val extension: String? = null,
    val error: String? = null,
)

@Serializable
data class HealthResponse(val ok: Boolean = false)

@Serializable
data class RequestEnrolResponse(
    val ok: Boolean = false,
    val status: String? = null,
    val error: String? = null,
)

@Serializable
data class LoginResponse(
    val token: String = "",
    val user: LoginUser? = null,
    val lines: List<LineInfo> = emptyList(),
)

@Serializable
data class LoginUser(
    val username: String? = null,
    val displayname: String? = null,
)

@Serializable
data class LineInfo(
    val did: String = "",
    val extension: String? = null,
    /** Set by the admin (BFF config); the app shows these read-only. */
    val capabilities: LineCapabilities? = null,
)

@Serializable
data class LineCapabilities(
    val voice: Boolean = true,
    val sms: Boolean = true,
    val mms: Boolean = true,
    val dnd: Boolean = false,
)

@Serializable
data class LinesResponse(val lines: List<LineInfo> = emptyList())

@Serializable
data class CallsResponse(
    val extension: String? = null,
    val permissions: CallPermissions = CallPermissions(),
    val calls: List<PbxCall> = emptyList(),
)

@Serializable
data class CallPermissions(val history: Boolean = false, val playback: Boolean = false, val download: Boolean = false)

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

@Serializable
data class SipCredentialsResponse(
    val ok: Boolean = false,
    val extension: String? = null,
    val secret: String? = null,
    val error: String? = null,
)

@Serializable
data class ThreadsResponse(
    val total: Int = 0,
    @SerialName("total_unread") val totalUnread: Int = 0,
    val threads: List<ThreadInfo> = emptyList(),
)

@Serializable
data class ThreadInfo(
    @SerialName("thread_id") val threadId: String? = null,
    val peer: String = "",
    val snippet: String? = null,
    val unread: Int = 0,
    /** Unix seconds, as a string (BFF stringifies ids and times). */
    @SerialName("last_message_at") val lastMessageAt: String? = null,
)

@Serializable
data class MessagesResponse(
    val messages: List<MessageInfo> = emptyList(),
)

@Serializable
data class MessageInfo(
    val id: Int = 0,
    val direction: String? = null,
    val body: String = "",
    val datetime: String? = null,
    /** Unix seconds. The BFF may send it as a number or a string. */
    val timestamp: kotlinx.serialization.json.JsonElement? = null,
    val media: List<MediaRef> = emptyList(),
) {
    val epochMs: Long?
        get() = epochMsOf((timestamp as? kotlinx.serialization.json.JsonPrimitive)?.content)
}

@Serializable
data class MediaRef(
    val name: String = "",
    val type: String? = null,
    val url: String? = null,
)

@Serializable
data class SendSmsResponse(
    val ok: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val detail: String? = null,
    @SerialName("id") val id: String? = null,
)

@Serializable
data class ApiError(
    val error: String? = null,
    val message: String? = null,
    val detail: String? = null,
)

@Serializable
data class VoicemailResponse(
    val extension: String? = null,
    val permissions: VoicemailPermissions = VoicemailPermissions(),
    /** The PBX's "My Voicemail" feature code (*97 by default); empty when the admin turned it off. */
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
