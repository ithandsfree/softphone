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

    fun session(token: String): LoginResponse {
        val text = get("/v1/session", token)
        val parsed = json.decodeFromString(LoginResponse.serializer(), text)
        return if (parsed.token.isBlank()) parsed.copy(token = token) else parsed
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
    val media: List<MediaRef> = emptyList(),
)

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
