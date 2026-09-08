package com.hhst.youtubelite.extractor.potoken;

import androidx.annotation.Nullable;

import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper;
import org.schabi.newpipe.extractor.services.youtube.YoutubePoTokenResult;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Provider that feeds PoToken data into extraction.
 */
@Singleton
public final class LitePoTokenProvider {
	private final PoTokenCoordinator coordinator;

	@Inject
	public LitePoTokenProvider(PoTokenCoordinator coordinator) {
		this.coordinator = coordinator;
	}

	@Nullable
	public YoutubePoTokenResult resolvePoToken(String videoId) {
		PoTokenResult result = coordinator.getWebClientPoToken(videoId);
		if (result == null || result.getPlayerPoToken() == null) {
			return null;
		}
		try {
			String clientVersion = YoutubeParsingHelper.getClientVersion();
			return new YoutubePoTokenResult(
							result.getVisitorData() != null ? result.getVisitorData() : "",
							clientVersion,
							result.getPlayerPoToken());
		} catch (Exception e) {
			return null;
		}
	}

	@Nullable
	public PoTokenResult getWebClientPoToken(String videoId) {
		return coordinator.getWebClientPoToken(videoId);
	}

	@Nullable
	public PoTokenResult getWebEmbedClientPoToken(String videoId) {
		return null;
	}
}
