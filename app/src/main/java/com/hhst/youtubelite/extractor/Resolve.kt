package com.hhst.youtubelite.extractor

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamExtractor
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

internal val forbiddenProfilesByVideo = java.util.concurrent.ConcurrentHashMap<String, MutableSet<org.schabi.newpipe.extractor.services.youtube.streams.ClientProfile>>()

/**
 * Shared stream and metadata extractor instance powered by PipePipeExtractor.
 */
internal class PlayerPage private constructor(
    val info: StreamInfo,
    val extractor: StreamExtractor,
) {
    companion object {
        private val CLIENT_LOCK = Any()
        private val FALLBACK_CLIENTS = listOf("mweb", "tv_simply", "tv_downgraded", "tv_samsung", "visionos")
        private val AUTH_FALLBACK_CLIENTS = listOf("mweb", "tv_simply", "tv_downgraded", "tv_samsung", "visionos")

        private fun setPlayerClient(client: String) {
            runCatching {
                val field = NewPipe::class.java.getDeclaredField("youtubePlayerClient")
                field.isAccessible = true
                field.set(null, client)
            }.onFailure {
                NewPipe.setYoutubePlayerClient(client)
            }
        }

        private fun hasPlayableMedia(info: StreamInfo): Boolean {
            val hasFormats = !info.videoStreams.isNullOrEmpty() ||
                !info.videoOnlyStreams.isNullOrEmpty() ||
                !info.audioStreams.isNullOrEmpty()
            val hasManifest = !info.hlsUrl.isNullOrBlank() || !info.dashMpdUrl.isNullOrBlank()
            return hasFormats || hasManifest
        }

        private fun fetchWithFallback(videoId: String): PlayerPage {
            val watchUrl = VideoId.watchUrl(videoId)
            var lastError: Throwable? = null
            var bestFallbackPage: PlayerPage? = null

            var browserResponse = AuthContextStore.getBrowserPlayerResponse(videoId)
            if (browserResponse == null && AuthContextStore.hasAuthCookies()) {
                val start = System.currentTimeMillis()
                while (browserResponse == null && System.currentTimeMillis() - start < 500L) {
                    try {
                        Thread.sleep(50)
                    } catch (_: InterruptedException) {
                        break
                    }
                    browserResponse = AuthContextStore.getBrowserPlayerResponse(videoId)
                }
            }

            val clients = if (browserResponse != null || AuthContextStore.hasAuthCookies()) {
                AUTH_FALLBACK_CLIENTS
            } else {
                FALLBACK_CLIENTS
            }

            for (client in clients) {
                val profile = when (client) {
                    "visionos" -> org.schabi.newpipe.extractor.services.youtube.streams.ClientProfile.VISIONOS
                    "tv_downgraded", "tv_samsung", "tv_simply" -> org.schabi.newpipe.extractor.services.youtube.streams.ClientProfile.TVHTML5
                    "mweb" -> org.schabi.newpipe.extractor.services.youtube.streams.ClientProfile.WEB
                    else -> null
                }
                if (profile != null && forbiddenProfilesByVideo[videoId]?.contains(profile) == true) {
                    if (client == "mweb" && AuthContextStore.getBrowserPlayerResponse(videoId) != null) {
                        forbiddenProfilesByVideo[videoId]?.remove(profile)
                    } else {
                        continue
                    }
                }
                try {
                    val page = synchronized(CLIENT_LOCK) {
                        setPlayerClient(client)
                        val extractor = ServiceList.YouTube.getStreamExtractor(watchUrl)
                        extractor.fetchPage()
                        val info = StreamInfo.getInfo(extractor)
                        PlayerPage(info, extractor)
                    }
                    val playable = hasPlayableMedia(page.info)
                    android.util.Log.i(
                        "LiTube_RESOLVE",
                        "client=$client videoId=$videoId playable=$playable " +
                            "video=${page.info.videoStreams?.size} videoOnly=${page.info.videoOnlyStreams?.size} audio=${page.info.audioStreams?.size}",
                    )
                    if (playable) {
                        return page
                    }
                    if (bestFallbackPage == null) {
                        bestFallbackPage = page
                    }
                } catch (t: Throwable) {
                    lastError = t
                    android.util.Log.w(
                        "LiTube_RESOLVE",
                        "client=$client videoId=$videoId failed: ${t.javaClass.simpleName} - ${t.message}",
                    )
                    // Fast-fail in offline unit tests where network is intentionally stubbed out
                    if (t is IOException && t.message == "offline epoch test") {
                        throw t
                    }
                }
            }

            bestFallbackPage?.let { return it }
            throw lastError ?: IllegalStateException("Failed to extract stream for $videoId")
        }

        fun shared(
            videoId: String,
            scope: CoroutineScope,
        ): () -> PlayerPage {
            val deferred = CompletableDeferred<PlayerPage>()
            val started = AtomicBoolean(false)
            return {
                if (started.compareAndSet(false, true)) {
                    scope.launch(Dispatchers.IO) {
                        try {
                            deferred.complete(fetchWithFallback(videoId))
                        } catch (t: Throwable) {
                            deferred.completeExceptionally(t)
                        }
                    }
                }
                runBlocking { deferred.await() }
            }
        }
    }
}

/** Cache hit or extraction, then persist [Metadata]. */
internal fun Promise<Metadata>.resolveMetadata(
    videoId: String,
    cache: Cache,
    player: () -> PlayerPage,
) {
    cache.getMetadata(videoId)?.let { cached ->
        set { copyFrom(cached) }
        return
    }
    set { fillFrom(player()) }
    cache.putMetadata(videoId, value)
}

/** Cache hit or extraction, then persist [Stream]. */
internal fun Promise<Stream>.resolveStream(
    videoId: String,
    cache: Cache,
    player: () -> PlayerPage,
) {
    cache.getStream(videoId)?.let { cached ->
        set { copyFrom(cached) }
        return
    }
    set { fillFrom(player()) }
    cache.putStream(videoId, value)
}

/** Cache hit or extraction, then persist [ChapterList]. */
internal fun Promise<ChapterList>.resolveChapters(
    videoId: String,
    cache: Cache,
    player: () -> PlayerPage,
) {
    cache.getChapters(videoId)?.let { cached ->
        set { copyFrom(cached) }
        return
    }
    val extractor = player().extractor
    val chapters = runCatching { extractor.streamSegments }.getOrDefault(emptyList()).map {
        Chapter(
            title = it.title.orEmpty(),
            startSeconds = it.startTimeSeconds,
            previewUrl = it.previewUrl,
        )
    }
    set { this.chapters = chapters }
    cache.putChapters(videoId, value)
}

private fun Metadata.fillFrom(page: PlayerPage) {
    val info = page.info
    val extractor = page.extractor
    id = info.id.orEmpty()
    title = info.name.orEmpty()
    author = runCatching { extractor.uploaderName }.getOrNull()
    description = runCatching { extractor.description?.content }.getOrNull()
    duration = runCatching { extractor.length }.getOrDefault(0L).coerceAtLeast(0L)
    thumbnailUrl = bestImageUrl(runCatching { extractor.thumbnails }.getOrDefault(emptyList()))
        ?: VideoId.thumbnailUrl(id)
    isLive = runCatching {
        info.streamType == StreamType.LIVE_STREAM ||
            info.streamType == StreamType.AUDIO_LIVE_STREAM
    }.getOrDefault(false)
}

private fun Stream.fillFrom(page: PlayerPage) {
    val info = page.info
    formats = buildList {
        info.videoOnlyStreams?.forEach { add(it.toFormat(videoOnly = true)) }
        info.audioStreams?.forEach { add(it.toFormat()) }
        info.videoStreams?.forEach { add(it.toFormat()) }
    }
        .filter { it.url.isNotBlank() }
        .sortedWith(
            compareByDescending<Format> { it.height }
                .thenByDescending { it.bitrate },
        )
    subtitles = runCatching { info.subtitles ?: page.extractor.subtitlesDefault }
        .getOrDefault(emptyList())
        .mapNotNull { track ->
            val url = track.content.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Subtitle(
                url = url,
                languageCode = track.languageTag.orEmpty(),
                autoGenerated = track.isAutoGenerated,
                mimeType = track.format?.mimeType.orEmpty(),
            )
        }
    dashUrl = info.dashMpdUrl?.takeIf { it.isNotBlank() }
    hlsUrl = info.hlsUrl?.takeIf { it.isNotBlank() }
}

private fun VideoStream.toFormat(videoOnly: Boolean = false): Format {
    val item = runCatching { itagItem }.getOrNull()
    val urlStr = content.orEmpty()
    val hasRanges = initStart >= 0 && initEnd >= initStart && indexEnd > indexStart
    val plan = buildRequestPlan(urlStr, item?.contentLength ?: -1L, adaptive = videoOnly || hasRanges)
    return Format(
        url = urlStr,
        height = height,
        width = width,
        bitrate = bitrate,
        fps = fps,
        qualityLabel = resolution.orEmpty(),
        codec = codec,
        container = format?.name,
        itag = itag.takeIf { it > 0 },
        videoOnly = videoOnly,
        mimeType = format?.mimeType.orEmpty(),
        initStart = initStart,
        initEnd = initEnd,
        indexStart = indexStart,
        indexEnd = indexEnd,
        approxDurationMs = item?.approxDurationMs ?: -1L,
        requestPlan = plan,
    )
}

private fun AudioStream.toFormat(): Format {
    val item = runCatching { itagItem }.getOrNull()
    val urlStr = content.orEmpty()
    val hasRanges = initStart >= 0 && initEnd >= initStart && indexEnd > indexStart
    val plan = buildRequestPlan(urlStr, item?.contentLength ?: -1L, adaptive = true || hasRanges)
    return Format(
        url = urlStr,
        bitrate = if (averageBitrate > 0) averageBitrate else bitrate,
        codec = codec,
        container = format?.name,
        itag = itag.takeIf { it > 0 },
        audioOnly = true,
        mimeType = format?.mimeType.orEmpty(),
        initStart = initStart,
        initEnd = initEnd,
        indexStart = indexStart,
        indexEnd = indexEnd,
        approxDurationMs = item?.approxDurationMs ?: -1L,
        sampleRate = item?.sampleRate ?: -1,
        audioChannels = item?.audioChannels ?: -1,
        audioLocale = audioLocale?.takeIf { it.isNotBlank() },
        audioTrackId = audioTrackId?.takeIf { it.isNotBlank() },
        audioTrackName = audioTrackName?.takeIf { it.isNotBlank() },
        audioTrackType = null,
        audioTrackOriginal = false,
        requestPlan = plan,
    )
}

internal fun buildRequestPlan(
    url: String,
    contentLength: Long = -1L,
    adaptive: Boolean = true,
): org.schabi.newpipe.extractor.services.youtube.streams.RequestPlan? {
    if (url.isBlank()) return null
    val clientParam = run {
        val amp = url.indexOf("&c=")
        val q = url.indexOf("?c=")
        val start = when {
            amp >= 0 && (q < 0 || amp < q) -> amp + 3
            q >= 0 -> q + 3
            else -> -1
        }
        if (start < 0) null
        else {
            val end = url.indexOf('&', start).let { if (it < 0) url.length else it }
            url.substring(start, end).ifEmpty { null }
        }
    }
    val profile = when (clientParam?.uppercase()) {
        "VISIONOS" -> org.schabi.newpipe.extractor.services.youtube.streams.ClientProfile.VISIONOS
        "TVHTML5", "TVHTML5_SIMPLY", "TVHTML5_SIMPLY_EMBEDDED_PLAYER" -> org.schabi.newpipe.extractor.services.youtube.streams.ClientProfile.TVHTML5
        "ANDROID" -> org.schabi.newpipe.extractor.services.youtube.streams.ClientProfile.ANDROID
        "ANDROID_VR" -> org.schabi.newpipe.extractor.services.youtube.streams.ClientProfile.ANDROID_VR
        "IOS" -> org.schabi.newpipe.extractor.services.youtube.streams.ClientProfile.IOS
        "WEB_EMBEDDED_PLAYER" -> org.schabi.newpipe.extractor.services.youtube.streams.ClientProfile.WEB_EMBEDDED_PLAYER
        "WEB", "MWEB" -> org.schabi.newpipe.extractor.services.youtube.streams.ClientProfile.WEB
        else -> org.schabi.newpipe.extractor.services.youtube.streams.ClientProfile.VISIONOS
    }
    val ua = when (clientParam?.uppercase()) {
        "VISIONOS" -> "com.google.visionos.youtube/1.02(RealityDevice14,1; U; CPU visionOS 25_6_0 like Mac OS X; en_US)"
        "TVHTML5" -> "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version"
        "TVHTML5_SIMPLY", "TVHTML5_SIMPLY_EMBEDDED_PLAYER" -> "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36,gzip(gfe)"
        "ANDROID" -> "com.google.android.youtube/21.03.36 (Linux; U; Android 15; US) gzip"
        "MWEB" -> "Mozilla/5.0 (iPad; CPU OS 16_7_10 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1,gzip(gfe)"
        else -> "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
    }
    val headers = when (clientParam?.uppercase()) {
        "TVHTML5", "TVHTML5_SIMPLY", "TVHTML5_SIMPLY_EMBEDDED_PLAYER", "MWEB", "WEB" -> mapOf(
            "Origin" to "https://www.youtube.com",
            "Referer" to "https://www.youtube.com/",
        )
        else -> emptyMap()
    }
    val resolvedLen = if (contentLength > 0) contentLength else parseContentLengthFromUrl(url)
    val isTvHtml5 = profile == org.schabi.newpipe.extractor.services.youtube.streams.ClientProfile.TVHTML5
    val isAndroid = profile == org.schabi.newpipe.extractor.services.youtube.streams.ClientProfile.ANDROID
    val isWeb = profile == org.schabi.newpipe.extractor.services.youtube.streams.ClientProfile.WEB ||
        profile == org.schabi.newpipe.extractor.services.youtube.streams.ClientProfile.WEB_EMBEDDED_PLAYER
    return org.schabi.newpipe.extractor.services.youtube.streams.RequestPlan(
        session = org.schabi.newpipe.extractor.services.youtube.streams.YoutubeSession(userAgent = ua),
        profile = profile,
        protocol = org.schabi.newpipe.extractor.services.youtube.streams.RequestPlan.Protocol.HTTPS,
        range = if (isAndroid) {
            if (adaptive) org.schabi.newpipe.extractor.services.youtube.streams.RequestPlan.Range.QUERY
            else org.schabi.newpipe.extractor.services.youtube.streams.RequestPlan.Range.HEADER
        } else {
            org.schabi.newpipe.extractor.services.youtube.streams.RequestPlan.Range.HEADER
        },
        allowPostFallback = !isTvHtml5 && !isWeb,
        headers = headers,
        userAgent = ua,
        resourceLength = resolvedLen,
    )
}

private fun parseContentLengthFromUrl(url: String): Long {
    val amp = url.indexOf("&clen=")
    val q = url.indexOf("?clen=")
    val start = when {
        amp >= 0 && (q < 0 || amp < q) -> amp + 6
        q >= 0 -> q + 6
        else -> return -1L
    }
    val end = url.indexOf('&', start).let { if (it < 0) url.length else it }
    return url.substring(start, end).toLongOrNull() ?: -1L
}

private fun bestImageUrl(images: List<Image>): String? {
    if (images.isEmpty()) return null
    val rank = mapOf(
        Image.ResolutionLevel.HIGH to 3,
        Image.ResolutionLevel.MEDIUM to 2,
        Image.ResolutionLevel.LOW to 1,
        Image.ResolutionLevel.UNKNOWN to 0,
    )
    return images.maxByOrNull { rank[it.estimatedResolutionLevel] ?: 0 }?.url
}
