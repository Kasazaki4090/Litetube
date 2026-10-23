@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.hhst.youtubelite.diagnostics

import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.hhst.youtubelite.extractor.ExtractionTestActivity
import com.hhst.youtubelite.core.MmkvJsonCache
import com.tencent.mmkv.MMKV
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

class DiagnosticExportAndroidTest {
    @get:Rule val activity = ActivityScenarioRule(ExtractionTestActivity::class.java)
    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private val gson = Gson()

    private fun rows(zip: ZipFile, name: String) = zip.getInputStream(zip.getEntry(name)).bufferedReader().readLines()
        .filter { it.isNotBlank() }.map { JsonParser.parseString(it).asJsonObject }

    @Test fun corruptStorageRetainsFailureWithoutTheCachedBody() {
        val root = DiagnosticContext(videoId = "wDIrpvH8MzE", generation = 1)
        val kv = MMKV.mmkvWithID("diagnostic-test-storage")
        try {
            kv.encode("probe:corrupt", "private-cache-content")
            val cache = MmkvJsonCache(kv, gson)
            AppLog.operation(AppLog.Category.STORAGE, "cache_read", root).finish(
                DiagnosticOutcome.FAILURE,
                failure = IOException("Storage corrupted"),
                fields = mapOf("http_status" to "500")
            )
            val archive = AppLog.export(app)
            ZipFile(archive).use { zip ->
                val timeline = rows(zip, "timeline.jsonl")
                val failure = timeline.firstOrNull { it.get("event").asString == "storage.cache_read.failed" }
                assertNotNull(failure)
                assertEquals("500", failure!!.getAsJsonObject("context")
                    .getAsJsonObject("fields").get("http_status").asString)
                assertFalse(timeline.joinToString().contains("private-cache-content"))
                assertTrue(zip.entries().asSequence().any { it.name.startsWith("incidents/") })
            }
        } finally { kv.clearAll(); kv.close() }
    }

    @Test fun http200WithNoBodyProgressRetainsHeadersAndCancelsWithoutRecovery() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("payload").setBodyDelay(40, TimeUnit.SECONDS))
            val context = DiagnosticContext(videoId = "wDIrpvH8MzE", taskId = "stall-transfer", generation = 8)
            val client = DiagnosticNetwork.install(OkHttpClient.Builder()).readTimeout(45, TimeUnit.SECONDS).build()
            val call = client.newCall(DiagnosticNetwork.tag(Request.Builder().url(server.url("/videoplayback?itag=95&sig=private-signature")).build(), context))
            val response = call.execute()
            assertEquals(200, response.code)
            val executor = Executors.newSingleThreadExecutor()
            val reading = executor.submit { runCatching { response.body!!.bytes() } }
            try {
                val deadline = SystemClock.elapsedRealtime() + 34_000
                while (SystemClock.elapsedRealtime() < deadline) { DiagnosticNetwork.sample(); Thread.sleep(200) }
                call.cancel(); reading.get(3, TimeUnit.SECONDS)
                ZipFile(AppLog.export(app)).use { zip ->
                    val timeline = rows(zip, "timeline.jsonl")
                    val stall = timeline.firstOrNull { it.get("event").asString == "transfer_progress.stalled" && it.getAsJsonObject("context")?.get("task_id")?.asString == "stall-transfer" }
                    assertNotNull("Actual transfer stall was not diagnosed", stall)
                    assertEquals("wDIrpvH8MzE", stall!!.getAsJsonObject("context").get("video_id").asString)
                    assertEquals(200, stall.getAsJsonObject("fields").get("http_status").asInt)
                    assertEquals(0, stall.getAsJsonObject("fields").get("bytes_read").asInt)
                    assertFalse(timeline.any { it.get("event").asString == "transfer_progress.recovered" && it.getAsJsonObject("context")?.get("task_id")?.asString == "stall-transfer" })
                    assertFalse(timeline.joinToString().contains("private-signature"))
                }
            } finally { call.cancel(); response.close(); executor.shutdownNow() }
        }
    }

    @Test fun decoderAndFirstFrameCallbacksKeepTheOldVideoIdentityAfterSwitch() {
        val origin = DiagnosticContext(videoId = "Wh9klmWAm5s", generation = 1)
        val item = MediaItem.Builder().setUri(Uri.parse("https://fixture.invalid/media"))
            .setMediaMetadata(MediaMetadata.Builder().setExtras(Bundle().apply { putString("litube.diagnostic_context", gson.toJson(origin)) }).build()).build()
        val timeline = object : Timeline() {
            override fun getWindowCount() = 1
            override fun getPeriodCount() = 1
            override fun getWindow(index: Int, window: Window, projection: Long): Window = window.apply { mediaItem = item }
            override fun getPeriod(index: Int, period: Period, setIds: Boolean): Period = period.set("fixture", "fixture", 0, 200_000_000, 0)
            override fun getIndexOfPeriod(uid: Any) = if (uid == "fixture") 0 else -1
            override fun getUidOfPeriod(index: Int): Any = "fixture"
        }
        val event = AnalyticsListener.EventTime(SystemClock.elapsedRealtime(), timeline, 0, null, 93_724, timeline, 0, null, 93_724, 0)
        val telemetry = PlaybackTelemetry()
        AppLog.operation(AppLog.Category.PLAYER, "playback", DiagnosticContext(videoId = "wDIrpvH8MzE", generation = 2))
        telemetry.onVideoCodecError(event, IllegalStateException("private-decoder-message"))
        telemetry.onRenderedFirstFrame(event, Any(), SystemClock.elapsedRealtime())
        ZipFile(AppLog.export(app)).use { zip ->
            val errors = rows(zip, "timeline.jsonl").filter { it.get("event").asString == "video_codec_error" }
            assertTrue(errors.isNotEmpty())
            assertTrue(errors.all { it.getAsJsonObject("context").get("video_id").asString == "Wh9klmWAm5s" })
            assertFalse(errors.joinToString().contains("private-decoder-message"))
        }
    }
}
