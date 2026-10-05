package net.ithandsfree.softphone.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import net.ithandsfree.softphone.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

class SoftphoneApi(
    private val baseUrl: String = BuildConfig.DEFAULT_API_BASE,
    private val client: OkHttpClient = defaultClient(),
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private fun root(): String = baseUrl.trimEnd('/')

    suspend fun health(): Boolean = withContext(Dispatchers.IO) {
        fetchHealth()?.ok == true
    }

    /** Public branding (tenant default skin) from `/v1/health`. */
    suspend fun fetchBranding(): BrandingInfo? = fetchHealth()?.branding

    suspend fun fetchHealth(): HealthResponse? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("${root()}/v1/health").get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            val text = resp.body?.string().orEmpty()
            runCatching { json.decodeFromString(HealthResponse.serializer(), text) }.getOrNull()
        }
    }

    /**
     * Self-service "Email me a setup link". Always returns a generic check-inbox
     * payload — the BFF does not reveal whether the address exists.
     */
    suspend fun requestEnrol(email: String): RequestEnrolResponse = withContext(Dispatchers.IO) {
        val payload = buildString {
            append('{')
            append("\"email\":").append(json.encodeToString(String.serializer(), email.trim()))
            append('}')
        }
        val req = Request.Builder()
            .url("${root()}/v1/request-enrol")
            .post(payload.toRequestBody(JSON))
            .header("Content-Type", "application/json")
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val parsed = runCatching {
                json.decodeFromString(RequestEnrolResponse.serializer(), text)
            }.getOrNull()
            if (!resp.isSuccessful) {
                val err = runCatching { json.decodeFromString(ApiError.serializer(), text) }.getOrNull()
                throw ApiException(err?.error ?: parsed?.error ?: "request_enrol_failed", resp.code)
            }
            parsed ?: RequestEnrolResponse(ok = true, status = "check_inbox", retryAfter = 30)
        }
    }

    suspend fun login(username: String, password: String): LoginResponse = withContext(Dispatchers.IO) {
        val payload = buildString {
            append('{')
            append("\"username\":").append(json.encodeToString(String.serializer(), username))
            append(',')
            append("\"password\":").append(json.encodeToString(String.serializer(), password))
            append('}')
        }
        val req = Request.Builder()
            .url("${root()}/v1/login")
            .post(payload.toRequestBody(JSON))
            .header("Content-Type", "application/json")
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val err = runCatching { json.decodeFromString(ApiError.serializer(), text) }.getOrNull()
                throw ApiException(err?.error ?: "login_failed", resp.code)
            }
            json.decodeFromString(LoginResponse.serializer(), text)
        }
    }

    /**
     * Resolve a pre-issued BFF enrol token (from email / HTTPS landing) to user + lines.
     * Same auth headers as other bearer routes (`X-IHF-Token`).
     */
    suspend fun session(token: String): LoginResponse = withContext(Dispatchers.IO) {
        val resp = get(token, "/v1/session", LoginResponse.serializer())
        // Echo the presented token so callers can store it like a login response.
        if (resp.token.isBlank()) resp.copy(token = token) else resp
    }

    suspend fun lines(token: String): List<LineInfo> = withContext(Dispatchers.IO) {
        linesResponse(token).lines
    }

    suspend fun linesResponse(token: String): LinesResponse = withContext(Dispatchers.IO) {
        get(token, "/v1/lines", LinesResponse.serializer())
    }

    suspend fun threads(token: String, did: String, limit: Int = 50): ThreadsResponse =
        withContext(Dispatchers.IO) {
            get(token, "/v1/lines/${enc(did)}/threads?limit=$limit", ThreadsResponse.serializer())
        }

    suspend fun messages(token: String, did: String, peer: String): List<ChatMessage> =
        withContext(Dispatchers.IO) {
            get(
                token,
                "/v1/lines/${enc(did)}/threads/${enc(peer)}/messages",
                MessagesResponse.serializer(),
            ).messages
        }

    suspend fun markThreadRead(token: String, did: String, peer: String): MarkReadResponse =
        withContext(Dispatchers.IO) {
            postEmpty(token, "/v1/lines/${enc(did)}/threads/${enc(peer)}/read", MarkReadResponse.serializer())
        }

    suspend fun markDidRead(token: String, did: String): MarkReadResponse =
        withContext(Dispatchers.IO) {
            postEmpty(token, "/v1/lines/${enc(did)}/read-all", MarkReadResponse.serializer())
        }

    suspend fun deleteThread(
        token: String,
        did: String,
        peer: String,
        threadId: String? = null,
    ): DeleteResponse = withContext(Dispatchers.IO) {
        val path = buildString {
            append("/v1/lines/${enc(did)}/threads/${enc(peer)}")
            if (!threadId.isNullOrBlank()) {
                append("?thread_id=").append(enc(threadId))
            }
        }
        delete(token, path, DeleteResponse.serializer())
    }

    suspend fun deleteMessage(token: String, did: String, messageId: Int): DeleteResponse =
        withContext(Dispatchers.IO) {
            delete(token, "/v1/lines/${enc(did)}/messages/$messageId", DeleteResponse.serializer())
        }

    /**
     * Fetch FreePBX SIP secret for REGISTER. Authenticated HTTPS only —
     * store encrypted on device; never put in SMS.
     */
    suspend fun sipCredentials(token: String, did: String): SipCredentialsResponse =
        withContext(Dispatchers.IO) {
            get(token, "/v1/lines/${enc(did)}/sip-credentials", SipCredentialsResponse.serializer())
        }

    /** Current FreePBX DND for the line’s extension. */
    suspend fun getDnd(token: String, did: String): DndResponse =
        withContext(Dispatchers.IO) {
            get(token, "/v1/lines/${enc(did)}/dnd", DndResponse.serializer())
        }

    /** Set FreePBX DND (AstDB + Custom:DND) for the line’s extension. */
    suspend fun setDnd(token: String, did: String, enabled: Boolean): DndResponse =
        withContext(Dispatchers.IO) {
            val payload = "{\"enabled\":${if (enabled) "true" else "false"}}"
            putJson(token, "/v1/lines/${enc(did)}/dnd", payload, DndResponse.serializer())
        }

    suspend fun sendSms(token: String, did: String, to: String, body: String) =
        withContext(Dispatchers.IO) {
            val payload = buildString {
                append('{')
                append("\"to\":").append(json.encodeToString(String.serializer(), to))
                append(',')
                append("\"body\":").append(json.encodeToString(String.serializer(), body))
                append('}')
            }
            val req = Request.Builder()
                .url("${root()}/v1/lines/${enc(did)}/messages")
                .post(payload.toRequestBody(JSON))
                .header("Content-Type", "application/json")
                .header(TOKEN_HEADER, token)
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val err = runCatching { json.decodeFromString(ApiError.serializer(), text) }.getOrNull()
                    val detail = listOfNotNull(err?.error, err?.message ?: err?.detail)
                        .joinToString(": ")
                        .ifBlank { text.ifBlank { "send_failed" } }
                    throw ApiException(detail, resp.code)
                }
            }
        }

    suspend fun sendMms(token: String, did: String, to: String, file: File) =
        withContext(Dispatchers.IO) {
            val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("to", to)
                .addFormDataPart(
                    "file",
                    file.name,
                    file.asRequestBody("application/octet-stream".toMediaType()),
                )
                .build()
            val req = Request.Builder()
                .url("${root()}/v1/lines/${enc(did)}/messages/media")
                .post(multipart)
                .header(TOKEN_HEADER, token)
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw ApiException("send_media_failed", resp.code)
            }
        }

    fun mediaUrl(name: String): String = "${root()}/v1/media/${enc(name)}"

    private fun <T> get(
        token: String,
        path: String,
        deserializer: kotlinx.serialization.DeserializationStrategy<T>,
    ): T {
        // One retry on transport blips (phone wake / radio settle after Doze).
        var lastIo: java.io.IOException? = null
        repeat(2) { attempt ->
            try {
                val req = Request.Builder()
                    .url("${root()}$path")
                    .get()
                    .header(TOKEN_HEADER, token)
                    .build()
                client.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        val err = runCatching { json.decodeFromString(ApiError.serializer(), text) }.getOrNull()
                        throw ApiException(err?.error ?: "request_failed", resp.code)
                    }
                    return json.decodeFromString(deserializer, text)
                }
            } catch (e: java.io.IOException) {
                lastIo = e
                if (attempt == 0) {
                    runCatching { Thread.sleep(500) }
                }
            }
        }
        throw lastIo ?: java.io.IOException("request_failed")
    }

    private fun <T> postEmpty(
        token: String,
        path: String,
        deserializer: kotlinx.serialization.DeserializationStrategy<T>,
    ): T {
        val req = Request.Builder()
            .url("${root()}$path")
            .post("{}".toRequestBody(JSON))
            .header("Content-Type", "application/json")
            .header(TOKEN_HEADER, token)
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val err = runCatching { json.decodeFromString(ApiError.serializer(), text) }.getOrNull()
                throw ApiException(err?.error ?: "request_failed", resp.code)
            }
            return json.decodeFromString(deserializer, text)
        }
    }

    private fun <T> putJson(
        token: String,
        path: String,
        payload: String,
        deserializer: kotlinx.serialization.DeserializationStrategy<T>,
    ): T {
        val req = Request.Builder()
            .url("${root()}$path")
            .put(payload.toRequestBody(JSON))
            .header("Content-Type", "application/json")
            .header(TOKEN_HEADER, token)
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val err = runCatching { json.decodeFromString(ApiError.serializer(), text) }.getOrNull()
                throw ApiException(err?.error ?: "request_failed", resp.code)
            }
            return json.decodeFromString(deserializer, text)
        }
    }

    private fun <T> delete(
        token: String,
        path: String,
        deserializer: kotlinx.serialization.DeserializationStrategy<T>,
    ): T {
        val req = Request.Builder()
            .url("${root()}$path")
            .delete()
            .header(TOKEN_HEADER, token)
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val err = runCatching { json.decodeFromString(ApiError.serializer(), text) }.getOrNull()
                throw ApiException(err?.error ?: "request_failed", resp.code)
            }
            return json.decodeFromString(deserializer, text)
        }
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, Charsets.UTF_8.name())

    companion object {
        const val TOKEN_HEADER = "X-IHF-Token"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient {
            val builder = OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
            // DEBUG only: lab tunnels (10.0.2.2 / 127.0.0.1) whose cert CN
            // does not match the tunneled hostname.
            if (BuildConfig.DEBUG) {
                val trustAll = object : javax.net.ssl.X509TrustManager {
                    override fun checkClientTrusted(
                        chain: Array<java.security.cert.X509Certificate>,
                        authType: String,
                    ) = Unit

                    override fun checkServerTrusted(
                        chain: Array<java.security.cert.X509Certificate>,
                        authType: String,
                    ) = Unit

                    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> =
                        emptyArray()
                }
                val sslContext = javax.net.ssl.SSLContext.getInstance("TLS")
                sslContext.init(null, arrayOf<javax.net.ssl.TrustManager>(trustAll), java.security.SecureRandom())
                builder.sslSocketFactory(sslContext.socketFactory, trustAll)
                builder.hostnameVerifier { _, _ -> true }
            }
            return builder.build()
        }

        fun forAccount(account: SoftphoneAccount): SoftphoneApi {
            val base = account.apiBaseUrl.ifBlank { BuildConfig.DEFAULT_API_BASE }
            return SoftphoneApi(base)
        }
    }
}

class ApiException(val error: String, val httpCode: Int) : Exception("$error ($httpCode)")
