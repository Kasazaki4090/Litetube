package org.schabi.newpipe.extractor.services.youtube.streams

import com.google.gson.JsonObject
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Compatibility shims for upstream v3.0.3 data models that previously came from
 * HydeYYHH's custom NewPipeExtractor fork, keeping Media3 playback, caching,
 * and download pipelines source-compatible when using PipePipeExtractor.
 */
enum class ClientProfile(val clientName: String) {
	VISIONOS("VISIONOS"),
	WEB("WEB"),
	WEB_SAFARI("WEB_SAFARI"),
	WEB_REMIX("WEB_REMIX"),
	WEB_EMBEDDED_PLAYER("WEB_EMBEDDED_PLAYER"),
	ANDROID("ANDROID"),
	IOS("IOS"),
	TVHTML5("TVHTML5"),
	ANDROID_VR("ANDROID_VR"),
	IOS_MUSIC("IOS_MUSIC"),
}

data class FormatKey(
	val itag: Int = 0,
	val audioTrack: String = "",
	val codec: String = "",
	val drc: Boolean = false,
	val hdr: Boolean = false,
	val protocol: RequestPlan.Protocol = RequestPlan.Protocol.HTTPS,
)

data class StreamDemand(
	val maxHeight: Int = 0,
	val audioTrackId: String? = null,
	val preferProgressive: Boolean = false,
	val supportedVideoCodecs: Set<String> = emptySet(),
) {
	fun cacheKey(): String =
		"$maxHeight:${audioTrackId.orEmpty()}:$preferProgressive:${supportedVideoCodecs.sorted().joinToString(",")}"
}

class YoutubeSession(
	val id: String = "",
	val account: Account = Account.ANONYMOUS,
	val accountIndex: Int = 0,
	val authorization: String? = null,
	val cookieHeader: String? = null,
	val dataSyncId: String? = null,
	val visitorData: String? = null,
	val userAgent: String = "",
	val networkGeneration: Long = 0L,
	val playerJsUrl: String? = null,
	val pageIdentity: String = "",
	val config: JsonObject = JsonObject(),
	private val cookieLookup: (String) -> String = { cookieHeader.orEmpty() },
) {
	val key: String
		get() = id

	enum class Account {
		ANONYMOUS,
		AUTHENTICATED,
		PREMIUM,
	}

	fun cookies(url: String): String = cookieLookup(url)

	fun scope(): String = "$id:${account.name}:$accountIndex:$networkGeneration"
}

data class RequestPlan(
	val session: YoutubeSession = YoutubeSession(),
	val profile: ClientProfile = ClientProfile.VISIONOS,
	val protocol: Protocol = Protocol.HTTPS,
	val range: Range = Range.HEADER,
	val allowPostFallback: Boolean = false,
	val headers: Map<String, String> = emptyMap(),
	val userAgent: String = session.userAgent,
	val resourceLength: Long = -1L,
	val chunkLimit: Long = 4L * 1024 * 1024,
	val expiresAtMillis: Long = Long.MAX_VALUE,
) {
	enum class Protocol {
		HTTPS,
		DASH,
		HLS,
	}

	enum class Range {
		NONE,
		HEADER,
		QUERY,
	}

	fun cookies(url: String): String = session.cookies(url)
}

class ExtractionContext(
	val diagnostics: Diagnostics? = null,
) {
	fun interface Diagnostics {
		fun event(
			stage: String,
			profile: String,
			detail: String,
			elapsedMillis: Long,
			httpStatus: Int,
		)
	}
}

class StreamHttpException(
	val status: Int,
	message: String? = "HTTP $status",
	val retryAfterMillis: Long? = null,
) : IOException(message) {
	companion object {
		@JvmStatic
		fun parseRetryAfter(raw: String?, nowMillis: Long): Long? {
			val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
			trimmed.toLongOrNull()?.let { seconds ->
				return (seconds * 1_000L).coerceAtLeast(0L)
			}
			return runCatching {
				val targetMillis =
					ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME)
						.toInstant()
						.toEpochMilli()
				(targetMillis - nowMillis).coerceAtLeast(0L)
			}.getOrNull()
		}
	}
}

