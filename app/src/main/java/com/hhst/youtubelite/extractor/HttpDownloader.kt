package com.hhst.youtubelite.extractor

import android.webkit.CookieManager
import com.google.gson.JsonParser
import com.hhst.youtubelite.core.Constants
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.CancellableCall
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal object AuthContextStore {
    @Volatile
    var visitorData: String? = null
        private set

    @Volatile
    var dataSyncId: String? = null
        private set

    @Volatile
    var sessionIndex: String? = null
        private set

    @Volatile
    var loggedIn: Boolean = false
        private set

    private data class BrowserPlayerEntry(
        val body: String,
        val capturedAtMs: Long,
    )

    private val browserPlayerResponses = LinkedHashMap<String, BrowserPlayerEntry>(16, 0.75f, true)

    fun updateFromBrowser(
        visitorData: String?,
        dataSyncId: String?,
        sessionIndex: String?,
        loggedIn: Boolean,
    ) {
        if (!visitorData.isNullOrBlank()) this.visitorData = visitorData.trim()
        if (!dataSyncId.isNullOrBlank()) this.dataSyncId = dataSyncId.trim()
        if (!sessionIndex.isNullOrBlank()) this.sessionIndex = sessionIndex.trim()
        this.loggedIn = loggedIn
    }

    fun putBrowserPlayerResponse(videoId: String?, responseBody: String?) {
        val id = VideoId.parse(videoId) ?: return
        val rawBody = responseBody?.takeIf { it.isNotBlank() && it.length <= 2 * 1024 * 1024 } ?: return
        runCatching {
            val parsed = JsonParser.parseString(rawBody).asJsonObject
            if (parsed.getAsJsonObject("videoDetails")?.get("videoId")?.asString != id) return
            if (parsed.getAsJsonObject("playabilityStatus")?.get("status")?.asString != "OK") return
            val streamingData = parsed.getAsJsonObject("streamingData") ?: return
            if (streamingData.has("serverAbrStreamingUrl")) {
                streamingData.remove("serverAbrStreamingUrl")
            }
            val body = parsed.toString()
            synchronized(browserPlayerResponses) {
                browserPlayerResponses[id] = BrowserPlayerEntry(body, System.currentTimeMillis())
                while (browserPlayerResponses.size > 16) {
                    browserPlayerResponses.remove(browserPlayerResponses.keys.first())
                }
            }
        }
    }

    fun getBrowserPlayerResponse(videoId: String?): String? {
        val id = VideoId.parse(videoId) ?: return null
        val now = System.currentTimeMillis()
        synchronized(browserPlayerResponses) {
            val entry = browserPlayerResponses[id] ?: return null
            if (now - entry.capturedAtMs > 180_000L) {
                browserPlayerResponses.remove(id)
                return null
            }
            return entry.body
        }
    }

    fun clearBrowserPlayerResponse(videoId: String?) {
        val id = VideoId.parse(videoId) ?: return
        synchronized(browserPlayerResponses) {
            browserPlayerResponses.remove(id)
        }
    }

    fun hasAuthCookies(): Boolean {
        val cookies = collectYoutubeCookies("https://www.youtube.com", null)
        return !extractCookieValue(cookies, "SAPISID").isNullOrBlank() ||
            !extractCookieValue(cookies, "__Secure-3PAPISID").isNullOrBlank() ||
            loggedIn
    }

    fun collectYoutubeCookies(url: String, existingCookies: List<String>?): String {
        val manager = runCatching { CookieManager.getInstance() }.getOrNull()
        val rawSources = buildList {
            if (manager != null) {
                runCatching { manager.getCookie("https://www.youtube.com") }.getOrNull()?.let { add(it) }
                runCatching { manager.getCookie("https://m.youtube.com") }.getOrNull()?.let { add(it) }
                runCatching { manager.getCookie(url) }.getOrNull()?.let { add(it) }
            }
            existingCookies?.forEach { if (it.isNotBlank()) add(it) }
        }
        val merged = LinkedHashMap<String, String>()
        for (source in rawSources) {
            for (part in source.split(';')) {
                val trimmed = part.trim()
                if (trimmed.isEmpty()) continue
                val eq = trimmed.indexOf('=')
                if (eq <= 0) continue
                val key = trimmed.substring(0, eq).trim()
                // WebView cookies are added first; do not let default SOCS=CAE= overwrite real user cookies
                if (!merged.containsKey(key)) {
                    merged[key] = trimmed
                }
            }
        }
        if (!merged.containsKey("PREF")) {
            merged["PREF"] = "PREF=f2=8000000"
        }
        if (!merged.containsKey("SOCS")) {
            merged["SOCS"] = "SOCS=CAE="
        }
        return merged.values.joinToString("; ")
    }

    fun extractCookieValue(cookieHeader: String?, name: String): String? {
        if (cookieHeader.isNullOrBlank()) return null
        val prefix = "$name="
        for (part in cookieHeader.split(';')) {
            val trimmed = part.trim()
            if (trimmed.startsWith(prefix)) {
                return trimmed.substring(prefix.length).takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    fun parseDataSyncId(raw: String?): Pair<String?, String?> {
        val clean = raw?.trim().orEmpty()
        if (clean.isEmpty()) return null to null
        val parts = clean.split("||")
        if (parts.size == 2) {
            val first = parts[0].trim().takeIf { it.isNotEmpty() }
            val second = parts[1].trim().takeIf { it.isNotEmpty() }
            return if (second != null && first != null) {
                second to first
            } else {
                first to null
            }
        }
        return clean to null
    }

    fun buildSapIsIdAuthorization(
        sapisid: String,
        origin: String,
        userId: String?,
        epochSeconds: Long = System.currentTimeMillis() / 1000L,
    ): String {
        val scope = buildList {
            if (!userId.isNullOrBlank()) add(userId.trim())
            add(epochSeconds.toString())
            add(sapisid.trim())
            add(origin.trim())
        }.joinToString(" ")
        val digest = MessageDigest.getInstance("SHA-1").digest(scope.toByteArray(StandardCharsets.UTF_8))
        val hash = buildString(digest.size * 2) {
            for (b in digest) {
                append(((b.toInt() ushr 4) and 0xF).toString(16))
                append((b.toInt() and 0xF).toString(16))
            }
        }
        val suffix = if (!userId.isNullOrBlank()) "_u" else ""
        val token = "${epochSeconds}_${hash}$suffix"
        return "SAPISIDHASH $token SAPISID1PHASH $token SAPISID3PHASH $token"
    }
}

/**
 * Transport bridge adapting OkHttpClient into PipePipeExtractor's Downloader.
 */
class HttpDownloader(
    private val client: OkHttpClient,
) : Downloader() {

    private fun buildOkHttpRequest(request: Request): okhttp3.Request {
        val httpMethod = request.httpMethod() ?: "GET"
        val url = request.url()
        val dataToSend = request.dataToSend()
        val requestBody = dataToSend?.toRequestBody()

        val builder = okhttp3.Request.Builder()
            .url(url)
            .method(httpMethod, requestBody)

        val requestHeaders = request.headers()
        requestHeaders?.forEach { (name, values) ->
            for (value in values) {
                builder.addHeader(name, value)
            }
        }

        val uri = runCatching { URI.create(url) }.getOrNull()
        val host = uri?.host?.lowercase().orEmpty()
        val path = uri?.path.orEmpty()
        val isYoutubeWebHost = host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtu.be"

        val existingUa = requestHeaders?.entries
            ?.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }
            ?.value?.firstOrNull()
        if (existingUa.isNullOrBlank() || (isYoutubeWebHost && existingUa.contains("Chrome/91."))) {
            builder.header("User-Agent", DESKTOP_WEB_USER_AGENT.takeIf { isYoutubeWebHost } ?: Constants.userAgent())
        }

        if (isYoutubeWebHost) {
            val existingCookies = requestHeaders?.entries
                ?.filter { it.key.equals("Cookie", ignoreCase = true) }
                ?.flatMap { it.value }
            val mergedCookie = AuthContextStore.collectYoutubeCookies(url, existingCookies)
            if (mergedCookie.isNotBlank()) {
                builder.header("Cookie", mergedCookie)
            }

            val sapisid = AuthContextStore.extractCookieValue(mergedCookie, "SAPISID")
                ?: AuthContextStore.extractCookieValue(mergedCookie, "__Secure-3PAPISID")
            runCatching {
                ServiceList.YouTube.setTokens(if (!sapisid.isNullOrBlank()) mergedCookie else null)
            }

            if (path.startsWith("/youtubei/")) {
                val payloadString = dataToSend?.let { String(it, StandardCharsets.UTF_8) }.orEmpty()
                val currentClient = NewPipe.getYoutubePlayerClient()
                val isTvOrAppClient = payloadString.contains(""""clientName":"TVHTML5"""") ||
                    payloadString.contains(""""clientName":"TVHTML5_SIMPLY"""") ||
                    payloadString.contains(""""clientName":"VISIONOS"""") ||
                    payloadString.contains(""""clientName":"ANDROID"""") ||
                    payloadString.contains(""""clientName":"IOS"""") ||
                    currentClient == "tv_downgraded" ||
                    currentClient == "tv_samsung" ||
                    currentClient == "tv_simply" ||
                    currentClient == "visionos"

                if (isTvOrAppClient) {
                    builder.removeHeader("Cookie")
                    builder.removeHeader("Authorization")
                    builder.removeHeader("Origin")
                    builder.removeHeader("X-Origin")
                    builder.removeHeader("X-Youtube-Bootstrap-Logged-In")
                    builder.removeHeader("X-Goog-AuthUser")
                    builder.removeHeader("X-Goog-PageId")
                    builder.removeHeader("X-Goog-Visitor-Id")
                } else {
                    val visitorId = AuthContextStore.visitorData
                        ?: AuthContextStore.extractCookieValue(mergedCookie, "VISITOR_INFO1_LIVE")
                    if (!visitorId.isNullOrBlank()) {
                        builder.header("X-Goog-Visitor-Id", visitorId)
                    }

                    if (!sapisid.isNullOrBlank()) {
                        val origin = if (host.startsWith("m.")) "https://m.youtube.com" else "https://www.youtube.com"
                        val (userId, pageId) = AuthContextStore.parseDataSyncId(AuthContextStore.dataSyncId)
                        builder.header(
                            "Authorization",
                            AuthContextStore.buildSapIsIdAuthorization(sapisid, origin, userId)
                        )
                        builder.header("Origin", origin)
                        builder.header("X-Origin", origin)
                        builder.header("X-Youtube-Bootstrap-Logged-In", "true")
                        builder.header("X-Goog-AuthUser", AuthContextStore.sessionIndex ?: "0")
                        if (!pageId.isNullOrBlank()) {
                            builder.header("X-Goog-PageId", pageId)
                        }
                    }
                }
            }
        } else {
            val cookie = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
            if (!cookie.isNullOrBlank() && existingCookiesHeader(requestHeaders) == null) {
                builder.header("Cookie", cookie)
            }
        }

        return builder.build()
    }

    private fun existingCookiesHeader(headers: Map<String, List<String>>?): String? =
        headers?.entries?.firstOrNull { it.key.equals("Cookie", ignoreCase = true) }?.value?.firstOrNull()

    private fun stripServerAbrStreamingUrl(body: String): String {
        if (!body.contains("serverAbrStreamingUrl")) return body
        return runCatching {
            val parsed = JsonParser.parseString(body).asJsonObject
            val streamingData = parsed.getAsJsonObject("streamingData") ?: return body
            if (streamingData.has("serverAbrStreamingUrl")) {
                streamingData.remove("serverAbrStreamingUrl")
                parsed.toString()
            } else {
                body
            }
        }.getOrDefault(body)
    }

    private fun sanitizeWebMetadataProbeResponse(request: Request, rawBodyString: String): String {
        val bodyString = if (request.url().contains("/youtubei/v1/player")) {
            stripServerAbrStreamingUrl(rawBodyString)
        } else {
            rawBodyString
        }
        val url = request.url()
        val payload = request.dataToSend()?.let { String(it, StandardCharsets.UTF_8) }.orEmpty()
        val videoId = VIDEO_ID_REGEX.find(payload)?.groupValues?.getOrNull(1)

        val isBotOrLoginRequired = bodyString.contains("LOGIN_REQUIRED") ||
            bodyString.contains("Sign in to confirm you") ||
            bodyString.contains("Sign in to confirm you’re not a bot") ||
            bodyString.contains("Sign in to confirm you're not a bot")
        if (!isBotOrLoginRequired) {
            return bodyString
        }
        val lower = bodyString.lowercase()
        if (!lower.contains("not a bot") && (
                lower.contains("confirm your age") ||
                    lower.contains("age-restricted") ||
                    lower.contains("inappropriate") ||
                    lower.contains("desktoplegacyagegatereason") ||
                    lower.contains("this video is private")
                )
        ) {
            return bodyString
        }
        if (videoId.isNullOrBlank()) return bodyString
        var browserResponse = AuthContextStore.getBrowserPlayerResponse(videoId)
        if (browserResponse == null) {
            val start = System.currentTimeMillis()
            while (browserResponse == null && System.currentTimeMillis() - start < 1000L) {
                try {
                    Thread.sleep(50)
                } catch (e: InterruptedException) {
                    break
                }
                browserResponse = AuthContextStore.getBrowserPlayerResponse(videoId)
            }
        }
        browserResponse?.let { return it }
        return """{"playabilityStatus":{"status":"OK"},"videoDetails":{"videoId":"$videoId"},"microformat":{"playerMicroformatRenderer":{"uploadDate":"1970-01-01","publishDate":"1970-01-01"}}}"""
    }

    override fun execute(request: Request): Response {
        val url = request.url()
        val payload = request.dataToSend()?.let { String(it, StandardCharsets.UTF_8) }.orEmpty()
        val videoId = VIDEO_ID_REGEX.find(payload)?.groupValues?.getOrNull(1)

        val clientName = NewPipe.getYoutubePlayerClient()
        val isMweb = clientName == "mweb" || clientName == "web"
        if (!videoId.isNullOrBlank() && url.contains("/youtubei/v1/player") && !url.contains("\$fields=microformat")) {
            var browserResponse = AuthContextStore.getBrowserPlayerResponse(videoId)
            if (browserResponse == null && (isMweb || AuthContextStore.hasAuthCookies())) {
                val start = System.currentTimeMillis()
                while (browserResponse == null && System.currentTimeMillis() - start < 1500L) {
                    try {
                        Thread.sleep(50)
                    } catch (e: InterruptedException) {
                        break
                    }
                    browserResponse = AuthContextStore.getBrowserPlayerResponse(videoId)
                }
            }
            if (browserResponse != null) {
                val bytes = browserResponse.toByteArray(Charsets.UTF_8)
                return Response(
                    200,
                    "OK",
                    emptyMap(),
                    browserResponse,
                    bytes,
                    url,
                )
            }
        }

        val okHttpRequest = buildOkHttpRequest(request)

        client.newCall(okHttpRequest).execute().use { response ->
            if (response.code == 429) throw ReCaptchaException("HTTP 429", url)
            val rawBytes = response.body?.bytes() ?: ByteArray(0)
            val rawBodyString = String(rawBytes, Charsets.UTF_8)
            val bodyString = sanitizeWebMetadataProbeResponse(request, rawBodyString)
            val bytes = if (bodyString === rawBodyString) rawBytes else bodyString.toByteArray(Charsets.UTF_8)
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                bodyString,
                bytes,
                response.request.url.toString()
            )
        }
    }

    override fun executeAsync(request: Request, callback: AsyncCallback): CancellableCall {
        val url = request.url()
        val payload = request.dataToSend()?.let { String(it, StandardCharsets.UTF_8) }.orEmpty()
        val videoId = VIDEO_ID_REGEX.find(payload)?.groupValues?.getOrNull(1)

        val clientName = NewPipe.getYoutubePlayerClient()
        val isMweb = clientName == "mweb" || clientName == "web"
        val okHttpRequest = buildOkHttpRequest(request)
        val call = client.newCall(okHttpRequest)
        val cancellableCall = CancellableCall(call)

        if (!videoId.isNullOrBlank() && url.contains("/youtubei/v1/player") && !url.contains("\$fields=microformat")) {
            var browserResponse = AuthContextStore.getBrowserPlayerResponse(videoId)
            if (browserResponse == null && (isMweb || AuthContextStore.hasAuthCookies())) {
                val start = System.currentTimeMillis()
                while (browserResponse == null && System.currentTimeMillis() - start < 1500L) {
                    try {
                        Thread.sleep(50)
                    } catch (e: InterruptedException) {
                        break
                    }
                    browserResponse = AuthContextStore.getBrowserPlayerResponse(videoId)
                }
            }
            if (browserResponse != null) {
                val bytes = browserResponse.toByteArray(Charsets.UTF_8)
                val result = Response(
                    200,
                    "OK",
                    emptyMap(),
                    browserResponse,
                    bytes,
                    url,
                )
                callback.onSuccess(result)
                cancellableCall.setFinished()
                return cancellableCall
            }
        }

        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                try {
                    callback.onError(e)
                } finally {
                    cancellableCall.setFinished()
                }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                try {
                    response.use { res ->
                        if (res.code == 429) {
                            callback.onError(ReCaptchaException("HTTP 429", url))
                            return
                        }
                        val rawBytes = res.body?.bytes() ?: ByteArray(0)
                        val rawBodyString = String(rawBytes, Charsets.UTF_8)
                        val bodyString = sanitizeWebMetadataProbeResponse(request, rawBodyString)
                        val bytes = if (bodyString === rawBodyString) rawBytes else bodyString.toByteArray(Charsets.UTF_8)
                        val result = Response(
                            res.code,
                            res.message,
                            res.headers.toMultimap(),
                            bodyString,
                            bytes,
                            res.request.url.toString()
                        )
                        callback.onSuccess(result)
                    }
                } catch (e: Exception) {
                    callback.onError(e)
                } finally {
                    cancellableCall.setFinished()
                }
            }
        })

        return cancellableCall
    }

    private companion object {
        const val DESKTOP_WEB_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
        val VIDEO_ID_REGEX = Regex(""""videoId"\s*:\s*"([a-zA-Z0-9_-]{11})"""")
    }
}
