package com.hhst.youtubelite.extractor

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.grack.nanojson.JsonParser
import com.hhst.youtubelite.diagnostics.AppLog
import com.hhst.youtubelite.diagnostics.DiagnosticContext
import com.hhst.youtubelite.diagnostics.DiagnosticOutcome
import kotlinx.coroutines.CoroutineScope
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.services.youtube.streams.ClientProfile
import org.schabi.newpipe.extractor.services.youtube.streams.StreamDemand
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * High-performance Stream & Metadata extraction entry point powered by PipePipeExtractor.
 */
class Extractor(
    downloader: HttpDownloader,
    private val cache: Cache,
    private val scope: CoroutineScope = Promise.DEFAULT_SCOPE,
) {
    private val inFlight = ConcurrentHashMap<String, Inflight>()
    private val streamEpochs = StreamEpochGuard()
    private val selectionLock = Any()
    private var selectedPreparation: Inflight? = null

    init {
        NewPipe.init(downloader)
        NewPipe.setYoutubePlayerClient("visionos")
    }

    @SuppressLint("JavascriptInterface")
    fun installBrowserCapture(view: WebView, generation: () -> Long) {
        runCatching {
            view.addJavascriptInterface(
                object {
                    @JavascriptInterface
                    fun postMessage(payload: String?) {
                        if (payload.isNullOrBlank()) return
                        runCatching {
                            generation()
                            val root = JsonParser.`object`().from(payload)
                            val config = root.getObject("configuration") ?: return
                            val visitorData = config.getString("VISITOR_DATA", null)
                            val dataSyncId = config.getString("DATASYNC_ID", null)
                            val sessionIndex = config.getString("SESSION_INDEX", null)
                            val loggedIn = config.getBoolean("LOGGED_IN", false)
                            AuthContextStore.updateFromBrowser(
                                visitorData = visitorData,
                                dataSyncId = dataSyncId,
                                sessionIndex = sessionIndex,
                                loggedIn = loggedIn,
                            )
                        }
                    }
                },
                "NativePlayerCapture"
            )
        }
    }

    fun initialize(): Boolean = true
    fun sessionStamp(): String = AuthContextStore.visitorData.orEmpty()
    fun recoveryScope(): String = if (AuthContextStore.hasAuthCookies()) "authenticated" else "anonymous"
    fun noteMediaForbidden(videoId: String, url: String, diagnosticContext: DiagnosticContext? = null): ClientProfile? = null
    fun extractionDiagnostics(): String =
        "PipePipeExtractor Engine (client=${NewPipe.getYoutubePlayerClient()}, auth=${AuthContextStore.hasAuthCookies()})"

    /**
     * Starts extraction for [urlOrId]. Reuses an in-flight [Extraction] for the same id.
     */
    fun extract(urlOrId: String, catalog: Boolean = false, demand: StreamDemand? = null, diagnosticContext: DiagnosticContext? = null): Extraction {
        return synchronized(selectionLock) { startExtraction(urlOrId, catalog, demand, false, diagnosticContext).extraction }
    }

    /**
     * Drops any cached stream for [urlOrId] and requests fresh stream URLs.
     */
    fun extractFresh(urlOrId: String, catalog: Boolean = false, demand: StreamDemand? = null, diagnosticContext: DiagnosticContext? = null): Extraction {
        val id = VideoId.parse(urlOrId) ?: urlOrId
        invalidateStream(id)
        return extract(urlOrId, catalog, demand, diagnosticContext)
    }

    fun preparePlayback(urlOrId: String, demand: StreamDemand? = null) {
        synchronized(selectionLock) {
            val slot = startExtraction(urlOrId, false, demand, true)
            if (selectedPreparation !== slot) selectedPreparation?.cancelPreparation()
            selectedPreparation = slot
        }
    }

    fun invalidateStream(urlOrId: String) {
        val id = VideoId.parse(urlOrId) ?: return
        streamEpochs.bump(id)
        cache.invalidateStream(id)
        inFlight.entries.removeIf { it.value.id == id }
    }

    suspend fun awaitMedia(urlOrId: String, diagnosticContext: DiagnosticContext? = null): Media {
        val extraction = extract(urlOrId, diagnosticContext = diagnosticContext)
        val metadata = extraction.metadata.await()
        val stream = extraction.stream.await()
        return Media(metadata, stream)
    }

    suspend fun awaitFreshMedia(urlOrId: String, diagnosticContext: DiagnosticContext? = null): Media {
        val extraction = extractFresh(urlOrId, diagnosticContext = diagnosticContext)
        val metadata = extraction.metadata.await()
        val stream = extraction.stream.await()
        return Media(metadata, stream)
    }

    private fun startExtraction(
        urlOrId: String,
        catalog: Boolean,
        demand: StreamDemand?,
        preparation: Boolean,
        diagnosticContext: DiagnosticContext? = null
    ): Inflight {
        val id = VideoId.parse(urlOrId)
            ?: throw IllegalArgumentException("Invalid YouTube url or id: $urlOrId")
        val (slot, created) = streamEpochs.withLock(id) {
            val epoch = streamEpochs.epochFor(id)
            val key = id + ":" + catalog + ":" + demand?.cacheKey().orEmpty()
            val existing = inFlight[key]?.takeIf { it.epoch == epoch } ?: selectedPreparation?.takeIf {
                it.epoch == epoch && it.matches(id, catalog, demand?.cacheKey().orEmpty())
            }
            if (existing != null && existing.active.get()) {
                if (!preparation) existing.claim()
                existing to false
            } else {
                val createdSlot = Inflight.create(
                    id = id,
                    cache = cache,
                    scope = scope,
                    streamEpochs = streamEpochs,
                    catalog = catalog,
                    demand = demand,
                    preparation = preparation,
                    diagnosticContext = diagnosticContext
                )
                inFlight[key] = createdSlot
                createdSlot to true
            }
        }
        if (!created) {
            slot.logJoin(diagnosticContext)
            return slot
        }

        val remaining = AtomicInteger(3)
        val onSettled = {
            if (remaining.decrementAndGet() == 0) {
                inFlight.entries.removeIf { it.value === slot }
            }
        }
        slot.extraction.metadata.whenDone(onSettled)
        slot.extraction.stream.whenDone(onSettled)
        slot.extraction.chapters.whenDone(onSettled)
        slot.start()
        return slot
    }
}

data class Media(
    val metadata: Metadata,
    val stream: Stream,
)

internal class Inflight(
    val id: String,
    val epoch: Long,
    val extraction: Extraction,
    val network: Boolean,
    val active: AtomicBoolean,
    @Volatile var preparation: Boolean,
    val catalog: Boolean,
    val demandKey: String,
    private val checkCurrent: () -> Boolean,
    val diagnostic: DiagnosticContext? = null,
) {
    fun claim() { preparation = false }
    fun cancelPreparation() {
        if (!preparation) return
        active.set(false)
        extraction.metadata.cancel()
        extraction.stream.cancel()
        extraction.chapters.cancel()
    }
    fun matches(id: String, catalog: Boolean, demandKey: String): Boolean =
        this.id == id && this.catalog == catalog && this.demandKey == demandKey
    fun logJoin(context: DiagnosticContext?) {
        AppLog.detail(AppLog.Category.EXTRACTOR, "inflight_join", mapOf("video_id" to id, "preparation" to preparation), context ?: diagnostic)
    }
    fun start() {
        extraction.metadata.start()
        extraction.stream.start()
        extraction.chapters.start()
    }

    companion object {
        fun create(
            id: String,
            cache: Cache,
            scope: CoroutineScope,
            streamEpochs: StreamEpochGuard,
            catalog: Boolean = false,
            demand: StreamDemand? = null,
            preparation: Boolean = false,
            diagnosticContext: DiagnosticContext? = null,
        ): Inflight {
            val epoch = streamEpochs.epochFor(id)
            val active = AtomicBoolean(true)
            val player = PlayerPage.shared(id, scope)
            val streamCache = EpochGuardedCache(cache, id, epoch, streamEpochs)
            val extraction = Extraction(
                metadata = Promise(Metadata(), scope, autostart = false) {
                    val operation = AppLog.operation(AppLog.Category.EXTRACTOR, "metadata", diagnosticContext?.child() ?: DiagnosticContext())
                    try {
                        it.resolveMetadata(id, cache, player)
                        operation.finish(DiagnosticOutcome.SUCCESS)
                    } catch (failure: Throwable) {
                        operation.finish(if (failure is kotlinx.coroutines.CancellationException) DiagnosticOutcome.CANCELLED else DiagnosticOutcome.FAILURE, failure = failure)
                        throw failure
                    }
                },
                stream = Promise(Stream(), scope, autostart = false) {
                    val operation = AppLog.operation(AppLog.Category.EXTRACTOR, "stream", diagnosticContext?.child() ?: DiagnosticContext(), mapOf("catalog" to catalog, "preparation" to preparation))
                    try {
                        it.resolveStream(id, streamCache, player)
                        operation.finish(DiagnosticOutcome.SUCCESS)
                    } catch (failure: Throwable) {
                        operation.finish(if (failure is kotlinx.coroutines.CancellationException) DiagnosticOutcome.CANCELLED else DiagnosticOutcome.FAILURE, failure = failure)
                        throw failure
                    }
                },
                chapters = Promise(ChapterList(), scope, autostart = false) {
                    it.resolveChapters(id, cache, player)
                },
            )
            return Inflight(id, epoch, extraction, true, active, preparation, catalog, demand?.cacheKey().orEmpty(), { true }, diagnosticContext)
        }
    }
}

internal class EpochGuardedCache(
    private val inner: Cache,
    private val id: String,
    private val epoch: Long,
    private val epochs: StreamEpochGuard,
) : Cache by inner {
    override fun getStream(videoId: String): Stream? {
        if (videoId != id) return inner.getStream(videoId)
        return epochs.withLock(id) {
            if (epochs.epochFor(id) != epoch) null
            else inner.getStream(videoId)
        }
    }

    override fun putStream(videoId: String, stream: Stream) {
        if (videoId != id) {
            inner.putStream(videoId, stream)
            return
        }
        epochs.withLock(id) {
            if (epochs.epochFor(id) != epoch) return@withLock
            inner.putStream(videoId, stream)
        }
    }
}

internal class StreamEpochGuard {
    private val epochs = ConcurrentHashMap<String, Long>()
    private val locks = ConcurrentHashMap<String, Any>()

    private fun lockFor(id: String): Any = locks.computeIfAbsent(id) { Any() }

    fun <T> withLock(id: String, block: () -> T): T =
        synchronized(lockFor(id)) { block() }

    fun epochFor(id: String): Long = epochs.getOrDefault(id, 0L)

    fun bump(id: String) {
        withLock(id) { bumpUnlocked(id) }
    }

    fun bumpUnlocked(id: String) {
        epochs.merge(id, 1L) { old, _ -> old + 1 }
    }
}
