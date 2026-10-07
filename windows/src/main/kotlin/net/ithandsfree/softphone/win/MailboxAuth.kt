package net.ithandsfree.softphone.win

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.awt.Desktop
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Mailbox contacts via the system browser. OAuth 2.0 authorization code with PKCE.
 * The mail app on this PC is not used. See DESKTOP-SPEC.md, "Mailbox contacts".
 * GPL-2.0.
 */
internal object MailboxAuth {
    enum class Provider(val title: String, val refreshKey: String) {
        MICROSOFT("Microsoft 365", "microsoft.refresh"),
        GOOGLE("Google", "google.refresh"),
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val random = SecureRandom()

    fun connect(provider: Provider): Int {
        val clientId = clientId(provider)
            ?: error("${provider.title} sign-in is not configured for this build yet.")
        val saved = refreshToken(provider)
        val token = if (saved != null) {
            runCatching { refresh(provider, clientId, saved) }.getOrElse {
                signIn(provider, clientId)
            }
        } else {
            signIn(provider, clientId)
        }
        val people = fetchContacts(provider, token.access)
        people.forEach { ContactBook.add(it.name, it.number) }
        return people.size
    }

    fun disconnect(provider: Provider) {
        val props = readStore()
        props.remove(provider.refreshKey)
        writeStore(props)
    }

    fun connected(provider: Provider): Boolean = refreshToken(provider) != null

    internal fun pkceChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    internal fun contactsFromMicrosoft(body: String): List<ContactBook.Person> {
        val root = json.parseToJsonElement(body).jsonObject
        return root.array("value").mapNotNull { row ->
            val person = row.jsonObject
            val number = person.string("mobilePhone")
                ?: person.array("businessPhones").firstString()
                ?: person.array("homePhones").firstString()
            val name = person.string("displayName").orEmpty()
            if (name.isBlank() || number.isNullOrBlank()) null else ContactBook.Person(name, number)
        }
    }

    internal fun contactsFromGoogle(body: String): List<ContactBook.Person> {
        val root = json.parseToJsonElement(body).jsonObject
        return root.array("connections").mapNotNull { row ->
            val person = row.jsonObject
            val name = person.array("names").firstObjectString("displayName")
            val number = person.array("phoneNumbers").firstObjectString("value")
            if (name.isNullOrBlank() || number.isNullOrBlank()) null else ContactBook.Person(name, number)
        }
    }

    private fun signIn(provider: Provider, clientId: String): Token {
        val verifier = randomToken(64)
        val state = randomToken(24)
        val reply = AtomicReference<String>()
        val failure = AtomicReference<String>()
        val done = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port = server.address.port
        val redirect = "http://127.0.0.1:$port/ihf-mailbox"
        server.createContext("/ihf-mailbox") { exchange ->
            val query = exchange.requestURI.rawQuery.orEmpty()
            val page = "You can close this window and return to IHF Phone."
            val bytes = page.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            val values = parseQuery(query)
            if (values["state"] != state) {
                failure.set("The sign-in reply did not match this phone.")
            } else if (!values["error"].isNullOrBlank()) {
                failure.set("Sign-in was cancelled.")
            } else if (values["code"].isNullOrBlank()) {
                failure.set("The sign-in reply had no code.")
            } else {
                reply.set(values["code"])
            }
            done.countDown()
        }
        server.start()
        try {
            val url = authorizeUrl(provider, clientId, redirect, state, pkceChallenge(verifier))
            if (!Desktop.isDesktopSupported()) error("This PC has no browser to open for sign-in.")
            Desktop.getDesktop().browse(URI(url))
            if (!done.await(3, TimeUnit.MINUTES)) error("Sign-in timed out. Try Connect again.")
            failure.get()?.let { error(it) }
            val code = reply.get() ?: error("The sign-in reply had no code.")
            return exchange(provider, clientId, redirect, verifier, code)
        } finally {
            server.stop(0)
        }
    }

    private fun authorizeUrl(provider: Provider, clientId: String, redirect: String, state: String, challenge: String): String {
        val query = linkedMapOf(
            "client_id" to clientId,
            "response_type" to "code",
            "redirect_uri" to redirect,
            "state" to state,
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
        )
        when (provider) {
            Provider.MICROSOFT -> {
                query["response_mode"] = "query"
                query["scope"] = "openid offline_access User.Read Contacts.Read"
                query["prompt"] = "select_account"
                return "https://login.microsoftonline.com/common/oauth2/v2.0/authorize?" + form(query)
            }
            Provider.GOOGLE -> {
                query["scope"] = "https://www.googleapis.com/auth/contacts.readonly"
                query["access_type"] = "offline"
                query["prompt"] = "consent"
                return "https://accounts.google.com/o/oauth2/v2/auth?" + form(query)
            }
        }
    }

    private fun exchange(provider: Provider, clientId: String, redirect: String, verifier: String, code: String): Token {
        val body = linkedMapOf(
            "client_id" to clientId,
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to redirect,
            "code_verifier" to verifier,
        )
        return tokenRequest(provider, body)
    }

    private fun refresh(provider: Provider, clientId: String, refreshToken: String): Token {
        val body = linkedMapOf(
            "client_id" to clientId,
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken,
        )
        if (provider == Provider.MICROSOFT) {
            body["scope"] = "openid offline_access User.Read Contacts.Read"
        }
        return tokenRequest(provider, body)
    }

    private fun tokenRequest(provider: Provider, body: Map<String, String>): Token {
        val endpoint = when (provider) {
            Provider.MICROSOFT -> "https://login.microsoftonline.com/common/oauth2/v2.0/token"
            Provider.GOOGLE -> "https://oauth2.googleapis.com/token"
        }
        val response = postForm(endpoint, body)
        val root = json.parseToJsonElement(response).jsonObject
        val access = root.string("access_token") ?: error(root.string("error_description") ?: "Sign-in did not return a token.")
        val refresh = root.string("refresh_token") ?: refreshToken(provider)
        if (!refresh.isNullOrBlank()) {
            val props = readStore()
            props.setProperty(provider.refreshKey, refresh)
            writeStore(props)
        }
        return Token(access)
    }

    private fun fetchContacts(provider: Provider, access: String): List<ContactBook.Person> {
        val people = mutableListOf<ContactBook.Person>()
        var url: String? = when (provider) {
            Provider.MICROSOFT -> "https://graph.microsoft.com/v1.0/me/contacts?\$top=100&\$select=displayName,mobilePhone,businessPhones,homePhones"
            Provider.GOOGLE -> "https://people.googleapis.com/v1/people/me/connections?personFields=names,phoneNumbers&pageSize=200"
        }
        var pages = 0
        while (url != null && pages < 10 && people.size < 1000) {
            val body = getJson(url, access)
            people += when (provider) {
                Provider.MICROSOFT -> contactsFromMicrosoft(body)
                Provider.GOOGLE -> contactsFromGoogle(body)
            }
            val root = json.parseToJsonElement(body).jsonObject
            url = when (provider) {
                Provider.MICROSOFT -> root.string("@odata.nextLink")
                Provider.GOOGLE -> root.string("nextPageToken")?.let { token ->
                    "https://people.googleapis.com/v1/people/me/connections?personFields=names,phoneNumbers&pageSize=200&pageToken=" +
                        encode(token)
                }
            }
            pages++
        }
        return people.distinctBy { it.number }
    }

    private fun clientId(provider: Provider): String? {
        val env = when (provider) {
            Provider.MICROSOFT -> System.getenv("IHF_MICROSOFT_CLIENT_ID")
            Provider.GOOGLE -> System.getenv("IHF_GOOGLE_CLIENT_ID")
        }
        if (!env.isNullOrBlank()) return env.trim()
        val key = when (provider) {
            Provider.MICROSOFT -> "microsoft.clientId"
            Provider.GOOGLE -> "google.clientId"
        }
        return hostProperty(key)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun hostProperty(key: String): String? {
        val props = Properties()
        val installed = try {
            val code = MailboxAuth::class.java.protectionDomain?.codeSource?.location
            code?.let { File(it.toURI()).parentFile }
        } catch (_: Exception) {
            null
        }
        val file = sequenceOf(
            File("host.local.properties"),
            installed?.let { File(it, "host.local.properties") },
            installed?.parentFile?.let { File(it, "host.local.properties") },
        ).firstOrNull { it != null && it.isFile } ?: return null
        file.inputStream().use { props.load(it) }
        return props.getProperty(key)
    }

    private fun refreshToken(provider: Provider): String? =
        readStore().getProperty(provider.refreshKey)?.takeIf { it.isNotBlank() }

    private fun readStore(): Properties {
        val props = Properties()
        val file = store()
        if (file.isFile) file.inputStream().use { props.load(it) }
        return props
    }

    private fun writeStore(props: Properties) {
        val file = store()
        file.parentFile?.mkdirs()
        file.outputStream().use { props.store(it, "IHF Phone mailbox") }
    }

    private fun store(): File {
        val root = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
            ?: System.getProperty("java.io.tmpdir")
        return File(File(root, "IHF Phone"), "mailbox.properties")
    }

    private fun postForm(url: String, fields: Map<String, String>): String {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 15000
        connection.readTimeout = 20000
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        connection.outputStream.use { it.write(form(fields).toByteArray(StandardCharsets.UTF_8)) }
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (connection.responseCode !in 200..299) {
            val detail = runCatching { json.parseToJsonElement(text).jsonObject.string("error_description") }.getOrNull()
            error(detail ?: "Sign-in was rejected.")
        }
        return text
    }

    private fun getJson(url: String, access: String): String {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 15000
        connection.readTimeout = 20000
        connection.setRequestProperty("Authorization", "Bearer $access")
        connection.setRequestProperty("Accept", "application/json")
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (connection.responseCode !in 200..299) error("Contacts were not returned.")
        return text
    }

    private fun form(fields: Map<String, String>): String =
        fields.entries.joinToString("&") { encode(it.key) + "=" + encode(it.value) }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

    private fun parseQuery(query: String): Map<String, String> =
        query.split('&').mapNotNull { part ->
            val cut = part.indexOf('=')
            if (cut <= 0) null else {
                val key = java.net.URLDecoder.decode(part.substring(0, cut), StandardCharsets.UTF_8)
                val value = java.net.URLDecoder.decode(part.substring(cut + 1), StandardCharsets.UTF_8)
                key to value
            }
        }.toMap()

    private fun randomToken(bytes: Int): String {
        val raw = ByteArray(bytes)
        random.nextBytes(raw)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
    }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.array(key: String): JsonArray = this[key] as? JsonArray ?: JsonArray(emptyList())

    private fun JsonArray.firstString(): String? =
        firstOrNull()?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonArray.firstObjectString(key: String): String? =
        firstOrNull()?.jsonObject?.string(key)

    private data class Token(val access: String)
}
