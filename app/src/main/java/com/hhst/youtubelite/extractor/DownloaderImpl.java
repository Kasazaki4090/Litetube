package com.hhst.youtubelite.extractor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hhst.youtubelite.Constant;

import org.schabi.newpipe.extractor.downloader.CancellableCall;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.inject.Inject;
import javax.inject.Singleton;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Bridge that adapts NewPipe extraction into the app extractor flow.
 */
@Singleton
public final class DownloaderImpl extends Downloader {
	private static final String YOUTUBE_RESTRICTED_MODE_COOKIE = "PREF=f2=8000000";
	private static final String YOUTUBE_WEB_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
					+ "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

	private final OkHttpClient client;
	private final ExtractionSessionScope scope;

	@Inject
	public DownloaderImpl(OkHttpClient client,
	                      ExtractionSessionScope scope) {
		this.client = client.newBuilder()
						.callTimeout(0L, TimeUnit.MILLISECONDS)
						.connectTimeout(20L, TimeUnit.SECONDS)
						.writeTimeout(30L, TimeUnit.SECONDS)
						.readTimeout(45L, TimeUnit.SECONDS)
						.build();
		this.scope = scope;
	}

	@NonNull
	<T> T withExtractionSession(@NonNull StreamInfoSupplier<T> supplier,
	                            @Nullable ExtractionSession session) throws ExtractionException, IOException {
		ExtractionSession previous = scope.get();
		scope.set(session);
		try {
			return supplier.get();
		} finally {
			scope.set(previous);
		}
	}

	@Override
	public org.schabi.newpipe.extractor.downloader.Response execute(@NonNull org.schabi.newpipe.extractor.downloader.Request request) throws IOException, ReCaptchaException {
		String httpMethod = request.httpMethod() != null ? request.httpMethod() : "GET";
		String url = request.url();
		final Map<String, List<String>> headers = request.headers();
		byte[] dataToSend = request.dataToSend();

		RequestBody requestBody = null;
		if (dataToSend != null) requestBody = RequestBody.create(dataToSend);

		String userAgent = Constant.USER_AGENT;
		if (url.contains("youtube.com") || url.contains("googlevideo.com")) {
			userAgent = YOUTUBE_WEB_USER_AGENT;
		}
		final Request.Builder builder = new Request.Builder().url(url).method(httpMethod, requestBody).header("User-Agent", userAgent);
		ExtractionSession session = scope.get();
		AuthContext auth = session != null ? session.getAuth() : null;
		String mergedCookies = mergeCookiesForUrl(
						url,
						auth != null ? auth.cookies() : null);

		if (headers != null) {
			for (final Map.Entry<String, List<String>> entry : headers.entrySet()) {
				String headerName = entry.getKey();
				if ("cookie".equalsIgnoreCase(headerName)) {
					mergedCookies = mergeCookieHeaders(
									mergedCookies,
									String.join("; ", entry.getValue()));
					continue;
				}
				builder.removeHeader(headerName);
				for (String value : entry.getValue()) {
					builder.addHeader(headerName, value);
				}
			}
		}
		YoutubeAuth.Result authHeaders = YoutubeAuth.headers(url, auth, System.currentTimeMillis());
		for (Map.Entry<String, String> entry : authHeaders.headers().entrySet()) {
			if (hasHeader(headers, entry.getKey())) {
				continue;
			}
			builder.header(entry.getKey(), entry.getValue());
		}
		if (!mergedCookies.isEmpty()) {
			builder.header("Cookie", mergedCookies);
		}

		Call call = client.newCall(builder.build());
		if (session != null) {
			session.register(call::cancel);
		}

		try (Response response = call.execute()) {
			if (response.code() == 429) {
				throw new ReCaptchaException("ReCaptcha Challenge requested", url);
			}

			int responseCode = response.code();
			String responseMessage = response.message();
			final Map<String, List<String>> responseHeaders = response.headers().toMultimap();
			ResponseBody responseBody = response.body();
			byte[] responseBodyBytes = responseBody.bytes();
			String responseBodyString = new String(responseBodyBytes, StandardCharsets.UTF_8);

			return new org.schabi.newpipe.extractor.downloader.Response(responseCode, responseMessage, responseHeaders, responseBodyString, responseBodyBytes, url);
		} catch (IOException e) {
			if (session != null && session.isCancelled()) {
				InterruptedIOException interrupted = new InterruptedIOException("Extraction canceled");
				interrupted.initCause(e);
				throw interrupted;
			}
			throw e;
		}
	}

	@Override
	public CancellableCall executeAsync(@NonNull org.schabi.newpipe.extractor.downloader.Request request, @NonNull AsyncCallback callback) {
		String httpMethod = request.httpMethod() != null ? request.httpMethod() : "GET";
		String url = request.url();
		final Map<String, List<String>> headers = request.headers();
		byte[] dataToSend = request.dataToSend();

		RequestBody requestBody = null;
		if (dataToSend != null) requestBody = RequestBody.create(dataToSend);

		String userAgent = Constant.USER_AGENT;
		if (url.contains("youtube.com") || url.contains("googlevideo.com")) {
			userAgent = YOUTUBE_WEB_USER_AGENT;
		}
		final Request.Builder builder = new Request.Builder().url(url).method(httpMethod, requestBody).header("User-Agent", userAgent);
		ExtractionSession session = scope.get();
		AuthContext auth = session != null ? session.getAuth() : null;
		String mergedCookies = mergeCookiesForUrl(
						url,
						auth != null ? auth.cookies() : null);

		if (headers != null) {
			for (final Map.Entry<String, List<String>> entry : headers.entrySet()) {
				String headerName = entry.getKey();
				if ("cookie".equalsIgnoreCase(headerName)) {
					mergedCookies = mergeCookieHeaders(
									mergedCookies,
									String.join("; ", entry.getValue()));
					continue;
				}
				builder.removeHeader(headerName);
				for (String value : entry.getValue()) {
					builder.addHeader(headerName, value);
				}
			}
		}
		YoutubeAuth.Result authHeaders = YoutubeAuth.headers(url, auth, System.currentTimeMillis());
		for (Map.Entry<String, String> entry : authHeaders.headers().entrySet()) {
			if (hasHeader(headers, entry.getKey())) {
				continue;
			}
			builder.header(entry.getKey(), entry.getValue());
		}
		if (!mergedCookies.isEmpty()) {
			builder.header("Cookie", mergedCookies);
		}

		Call call = client.newCall(builder.build());
		if (session != null) {
			session.register(call::cancel);
		}

		final CancellableCall cancellableCall = new CancellableCall(call);
		call.enqueue(new Callback() {
			@Override
			public void onFailure(@NonNull Call call, @NonNull IOException e) {
				callback.onError(e);
				cancellableCall.setFinished();
			}

			@Override
			public void onResponse(@NonNull Call call, @NonNull Response response) {
				if (response.code() == 429) {
					callback.onError(new ReCaptchaException("ReCaptcha Challenge requested", url));
					cancellableCall.setFinished();
					response.close();
					return;
				}

				try {
					int responseCode = response.code();
					String responseMessage = response.message();
					final Map<String, List<String>> responseHeaders = response.headers().toMultimap();
					ResponseBody responseBody = response.body();
					byte[] responseBodyBytes = responseBody.bytes();
					String responseBodyString = new String(responseBodyBytes, StandardCharsets.UTF_8);

					callback.onSuccess(new org.schabi.newpipe.extractor.downloader.Response(responseCode, responseMessage, responseHeaders, responseBodyString, responseBodyBytes, url));
				} catch (Exception e) {
					callback.onError(e);
				} finally {
					cancellableCall.setFinished();
					response.close();
				}
			}
		});

		return cancellableCall;
	}

	@NonNull
	String mergeCookiesForUrl(@NonNull String url,
	                          @Nullable String cookies) {
		String restrictedModeCookie = getRestrictedModeCookie(url, cookies);
		return Stream.of(cookies, restrictedModeCookie)
						.filter(Objects::nonNull)
						.flatMap(value -> Arrays.stream(value.split("; *")))
						.filter(s -> !s.isEmpty())
						.collect(Collectors.collectingAndThen(
										Collectors.toCollection(LinkedHashSet::new),
										set -> String.join("; ", set)));
	}

	@NonNull
	private String mergeCookieHeaders(@Nullable String first,
	                                  @Nullable String second) {
		return Stream.of(first, second)
						.filter(Objects::nonNull)
						.flatMap(value -> Arrays.stream(value.split("; *")))
						.filter(s -> !s.isEmpty())
						.collect(Collectors.collectingAndThen(
										Collectors.toCollection(LinkedHashSet::new),
										set -> String.join("; ", set)));
	}

	@Nullable
	private String getRestrictedModeCookie(@NonNull String url,
	                                       @Nullable String cookies) {
		if (!isYoutubeHost(getHost(url))) return null;
		if (cookies != null && cookies.contains("PREF=")) return null;
		return YOUTUBE_RESTRICTED_MODE_COOKIE;
	}

	@Nullable
	private String getHost(@NonNull String url) {
		try {
			return URI.create(url).getHost();
		} catch (IllegalArgumentException ignored) {
			return null;
		}
	}

	private boolean isYoutubeHost(@Nullable String host) {
		if (host == null) return false;
		String lowerHost = host.toLowerCase(Locale.US);
		return lowerHost.equals("youtu.be")
						|| lowerHost.equals(Constant.YOUTUBE_DOMAIN)
						|| lowerHost.endsWith("." + Constant.YOUTUBE_DOMAIN);
	}

	private boolean hasHeader(@Nullable Map<String, List<String>> headers,
	                          @NonNull String name) {
		if (headers == null) {
			return false;
		}
		for (String key : headers.keySet()) {
			if (name.equalsIgnoreCase(key)) {
				return true;
			}
		}
		return false;
	}

/**
 * Contract for app logic.
 */
	@FunctionalInterface
	interface StreamInfoSupplier<T> {
		T get() throws ExtractionException, IOException;
	}
}
