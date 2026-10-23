package com.hhst.youtubelite.extractor

import android.webkit.CookieManager
import com.hhst.youtubelite.core.Constants
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException

/**
 * Transport bridge adapting OkHttpClient into PipePipeExtractor's Downloader.
 */
class HttpDownloader(
    private val client: OkHttpClient,
) : Downloader() {
    override fun execute(request: Request): Response {
        val httpMethod = request.httpMethod() ?: "GET"
        val url = request.url()
        val dataToSend = request.dataToSend()
        val requestBody = dataToSend?.toRequestBody()

        val builder = okhttp3.Request.Builder()
            .url(url)
            .method(httpMethod, requestBody)

        request.headers()?.forEach { (name, values) ->
            for (value in values) {
                builder.addHeader(name, value)
            }
        }

        if (builder.build().header("User-Agent") == null) {
            builder.header("User-Agent", Constants.userAgent())
        }

        val cookie = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
        if (!cookie.isNullOrBlank() && builder.build().header("Cookie") == null) {
            builder.header("Cookie", cookie)
        }

        client.newCall(builder.build()).execute().use { response ->
            if (response.code == 429) throw ReCaptchaException("HTTP 429", url)
            val bytes = response.body?.bytes() ?: ByteArray(0)
            val bodyString = String(bytes, Charsets.UTF_8)
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
}
