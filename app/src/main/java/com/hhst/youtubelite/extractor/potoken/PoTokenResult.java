package com.hhst.youtubelite.extractor.potoken;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public final class PoTokenResult {
	@Nullable
	private String visitorData;
	@Nullable
	private String playerPoToken;
	@Nullable
	private String streamingPoToken;
}
