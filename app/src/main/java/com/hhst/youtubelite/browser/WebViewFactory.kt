package com.hhst.youtubelite.browser

import com.hhst.youtubelite.diagnostics.*
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import android.webkit.SslErrorHandler
import android.webkit.RenderProcessGoneDetail
import android.net.http.SslError

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.createBitmap
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.hhst.youtubelite.R
import com.hhst.youtubelite.core.Constants
import com.hhst.youtubelite.downloader.webview.DownloadWebBridge
import com.hhst.youtubelite.extension.ExtensionInjector
import com.hhst.youtubelite.extension.ExtensionManager
import com.hhst.youtubelite.extractor.Extractor
import com.hhst.youtubelite.gallery.GalleryActivity
import com.hhst.youtubelite.net.NetTracer
import com.hhst.youtubelite.net.PageScript
import com.hhst.youtubelite.ui.theme.YtRed
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Builds a swipe-refresh WebView host for the mobile site. */
object WebViewFactory {
    private val cookieWriter by lazy {
        Executors.newSingleThreadExecutor { task -> Thread(task, "cookie-persistence").apply { isDaemon = true } }
    }

    internal fun persistCookies() {
        cookieWriter.execute { runCatching { CookieManager.getInstance().flush() } }
    }

    internal fun prepareBackground(webView: WebView, url: String) {
        val dark = webView.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        webView.setBackgroundColor(if (PageKind.isShorts(url) || dark) Color.BLACK else Color.WHITE)
    }

    internal fun syncTheme(webView: WebView, isDark: Boolean) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                    WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, isDark)
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                    @Suppress("DEPRECATION")
                    WebSettingsCompat.setForceDark(
                        webView.settings,
                        if (isDark) WebSettingsCompat.FORCE_DARK_ON else WebSettingsCompat.FORCE_DARK_OFF,
                    )
                }
                if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY)) {
                    @Suppress("DEPRECATION")
                    WebSettingsCompat.setForceDarkStrategy(
                        webView.settings,
                        WebSettingsCompat.DARK_STRATEGY_PREFER_WEB_THEME_OVER_USER_AGENT_DARKENING,
                    )
                }
            }
        }
        runCatching {
            val cookieManager = CookieManager.getInstance()
            val cookies = cookieManager.getCookie("https://m.youtube.com") ?: ""
            var prefValue: String? = null
            for (cookie in cookies.split(";")) {
                val trimmed = cookie.trim()
                if (trimmed.startsWith("PREF=")) {
                    prefValue = trimmed.substring(5)
                    break
                }
            }
            val f6Val = if (isDark) "f6=400" else "f6=10000"
            val newPref = when {
                prefValue == null -> f6Val
                prefValue.contains(Regex("f6=[0-9a-fA-F]+")) ->
                    prefValue.replace(Regex("f6=[0-9a-fA-F]+"), f6Val)
                else -> "$prefValue&$f6Val"
            }
            cookieManager.setCookie(
                "https://www.youtube.com",
                "PREF=$newPref; Domain=.youtube.com; Path=/; Secure; SameSite=None",
            )
            cookieManager.setCookie(
                "https://m.youtube.com",
                "PREF=$newPref; Domain=.youtube.com; Path=/; Secure; SameSite=None",
            )
            persistCookies()
        }
        val currentUrl = webView.url
        if (!currentUrl.isNullOrBlank()) {
            webView.setBackgroundColor(if (PageKind.isShorts(currentUrl) || isDark) Color.BLACK else Color.WHITE)
        }
        if (UrlPolicy.shouldInject(currentUrl)) {
            val js = """
                (function(dark) {
                  var doc = document.documentElement;
                  if (!doc) return;
                  doc.style.colorScheme = dark ? 'dark' : 'light';
                  if (dark) {
                    doc.setAttribute('dark', 'true');
                    doc.classList.add('dark');
                    if (document.body) {
                      document.body.setAttribute('dark', 'true');
                      document.body.classList.add('dark');
                    }
                    document.querySelectorAll('[dark]').forEach(function(el) { el.setAttribute('dark', 'true'); });
                  } else {
                    doc.removeAttribute('dark');
                    doc.classList.remove('dark');
                    if (document.body) {
                      document.body.removeAttribute('dark');
                      document.body.classList.remove('dark');
                    }
                    document.querySelectorAll('[dark]').forEach(function(el) { el.removeAttribute('dark'); });
                    document.querySelectorAll('.dark').forEach(function(el) { el.classList.remove('dark'); });
                  }
                  if (!dark) {
                    doc.style.backgroundColor = '#ffffff';
                    if (document.body) document.body.style.backgroundColor = '#ffffff';
                  } else {
                    doc.style.backgroundColor = '#0f0f0f';
                    if (document.body) document.body.style.backgroundColor = '#0f0f0f';
                  }
                  try {
                    var data = JSON.parse(localStorage.getItem('yt-player-sticky-settings') || '{}');
                    if (data.values) {
                      data.values.dark = dark;
                      localStorage.setItem('yt-player-sticky-settings', JSON.stringify(data));
                    }
                  } catch(e) {}
                  var styles = dark ? {
                    '--yt-spec-text-primary': '#ffffff',
                    '--yt-spec-text-secondary': '#aaaaaa',
                    '--yt-spec-icon-inactive': '#ffffff',
                    '--yt-spec-icon-active-other': '#ffffff',
                    '--yt-spec-icon-disabled': '#717171',
                    '--yt-spec-wordmark-text': '#ffffff',
                    '--yt-spec-general-background-a': '#0f0f0f',
                    '--yt-spec-brand-background-solid': '#0f0f0f',
                    '--yt-spec-raised-background': '#212121',
                    '--yt-spec-menu-background': '#282828',
                    '--yt-spec-base-background': '#0f0f0f',
                    '--yt-spec-10-percent-layer': 'rgba(255, 255, 255, 0.1)',
                    '--yt-spec-static-brand-white': '#ffffff'
                  } : {
                    '--yt-spec-text-primary': '#030303',
                    '--yt-spec-text-secondary': '#606060',
                    '--yt-spec-icon-inactive': '#030303',
                    '--yt-spec-icon-active-other': '#030303',
                    '--yt-spec-icon-disabled': '#909090',
                    '--yt-spec-wordmark-text': '#030303',
                    '--yt-spec-general-background-a': '#ffffff',
                    '--yt-spec-brand-background-solid': '#ffffff',
                    '--yt-spec-raised-background': '#f9f9f9',
                    '--yt-spec-menu-background': '#ffffff',
                    '--yt-spec-base-background': '#ffffff',
                    '--yt-spec-10-percent-layer': 'rgba(0, 0, 0, 0.1)',
                    '--yt-spec-static-brand-white': '#ffffff'
                  };
                  for (var prop in styles) {
                    if (Object.prototype.hasOwnProperty.call(styles, prop)) {
                      doc.style.setProperty(prop, styles[prop], 'important');
                      if (document.body) document.body.style.setProperty(prop, styles[prop], 'important');
                    }
                  }
                  var fixTag = document.getElementById('litube-theme-fix');
                  if (!fixTag) {
                    fixTag = document.createElement('style');
                    fixTag.id = 'litube-theme-fix';
                    (document.head || doc).appendChild(fixTag);
                  }
                  fixTag.textContent = dark ? '' :
                    'html:not([dark]) ytm-mobile-topbar-renderer, ' +
                    'html:not([dark]) ytm-mobile-topbar, ' +
                    'html:not([dark]) ytm-mobile-app-bar, ' +
                    'html:not([dark]) #masthead, ' +
                    'html:not([dark]) .mobile-topbar-header { ' +
                    'background-color: #ffffff !important; background: #ffffff !important; } ' +
                    'html:not([dark]) ytm-mobile-topbar-renderer .topbar-icons button, ' +
                    'html:not([dark]) ytm-mobile-topbar-renderer .topbar-icons a, ' +
                    'html:not([dark]) ytm-mobile-topbar-renderer .topbar-icons yt-icon, ' +
                    'html:not([dark]) ytm-mobile-topbar-renderer .topbar-icons c3-icon, ' +
                    'html:not([dark]) ytm-mobile-topbar-renderer .topbar-icons span { ' +
                    'color: #030303 !important; fill: currentColor !important; } ' +
                    'html:not([dark]) ytm-mobile-topbar-renderer button:not(ytm-home-logo *) { ' +
                    'color: #030303 !important; } ' +
                    'html:not([dark]) ytm-mobile-topbar-renderer button:not(ytm-home-logo *) svg { ' +
                    'fill: currentColor !important; } ' +
                    'html:not([dark]) ytm-home-logo, ' +
                    'html:not([dark]) ytm-home-logo * { ' +
                    'color: #030303 !important; } ' +
                    'html:not([dark]) ytm-home-logo [fill="currentColor"], ' +
                    'html:not([dark]) ytm-home-logo g[fill="currentColor"] { ' +
                    'fill: #030303 !important; } ' +
                    'html:not([dark]) ytm-home-logo svg path:not(#rectangle):not([fill*="#FF00"]):not([fill*="#ff00"]):not([fill="#f00"]):not([fill="#F00"]) { ' +
                    'fill: #030303 !important; } ' +
                    'html:not([dark]) ytm-home-logo svg polygon { ' +
                    'fill: #ffffff !important; }';
                  var chatBox = document.getElementById('live_chat_container');
                  if (chatBox) chatBox.style.backgroundColor = dark ? '#0f0f0f' : '#ffffff';
                  var app = document.querySelector('ytm-app');
                  if (app && typeof app.setTheme === 'function') {
                    try { app.setTheme(dark ? 'DARK' : 'LIGHT'); } catch(e) {}
                  }
                  window.dispatchEvent(new Event('resize'));
                })($isDark);
            """.trimIndent()
            webView.evaluateJavascript(js, null)
        }
    }


    private const val NAV_JS = "script/nav.js"
    private const val INNERTUBE_JS = "script/innertube.js"
    private const val PLAYER_HOOK_JS = "script/player-hook.js"
    private const val DISLIKES_JS = "script/display_dislikes.js"
    private const val HIDE_SHORTS_JS = "script/hide_shorts.js"
    private const val SHORTS_ADS_JS = "script/remove_shorts_ads.js"
    private const val SUBSCRIBE_BUTTON_JS = "script/subscribe_button.js"
    private const val CORE_JS = "script/core.js"

    private val coreScript = PageScript(CORE_JS, "Core")
    private val navScript = PageScript(NAV_JS, "Nav")
    private val uiScript = PageScript("script/ui.js", "Ui")
    private val playerHookScript = PageScript(PLAYER_HOOK_JS, "PlayerHook")
    private val dislikesScript = PageScript(DISLIKES_JS, "DisplayDislikes")
    private val hideShortsScript = PageScript(HIDE_SHORTS_JS, "HideShorts")
    private val shortsAdsScript = PageScript(SHORTS_ADS_JS, "RemoveShortsAds")
    private val subscribeButtonScript = PageScript(SUBSCRIBE_BUTTON_JS, "SubscribeButton")
    private val shortsScript = PageScript("script/shorts.js", "Shorts")
    private val shortsQualityScript = PageScript("script/shorts-quality.js", "ShortsQuality")
    private val galleryScript = PageScript("script/gallery.js", "Gallery")
    private val adsScript = PageScript("script/ads.js", "Ads")
    private val downloadScript = PageScript(DownloadWebBridge.ASSET, "Download")

    @SuppressLint("SetJavaScriptEnabled")
    fun create(
        context: Context,
        callbacks: WebViewCallbacks,
        extensionManager: ExtensionManager,
        onOpenExtension: () -> Unit,
        onOpenDownloads: () -> Unit,
        onOpenWith: (String) -> Unit,
        onAbout: () -> Unit,
        onRefresh: (WebView) -> Unit,
        extractor: Extractor,
        playerHooks: PlayerHooks,
        onAddToQueue: ((Bridge.QueueItemJson?) -> Unit)? = null,
        onShowMediaItemMenu: ((Bridge.QueueItemJson) -> Unit)? = null,
        onPlaylistPresence: ((Boolean) -> Unit)? = null,
        onMicrophoneRequest: ((onResult: (Boolean) -> Unit) -> Unit)? = null,
        tabId: Long = 0L,
    ): BrowserHost {
        val appContext = context.applicationContext
        val injector = ExtensionInjector(appContext)
        val transientUi = MutableStateFlow(TransientUi())
        val bridge = Bridge(
            onOpenTab = callbacks::onOpenTab,
            onOpenExtension = onOpenExtension,
            onOpenDownloads = onOpenDownloads,
            onOpenWith = onOpenWith,
            onAbout = onAbout,
            extensionManager = extensionManager,
            extractor = extractor,
            playerHooks = playerHooks,
            onAddToQueue = onAddToQueue,
            onShowMediaItemMenu = onShowMediaItemMenu,
            onPlaylistPresence = onPlaylistPresence,
            tabId = tabId,
            canPlay = callbacks::isActiveDocument,
            isActive = callbacks::isActiveTab,
            inheritShorts = callbacks::shouldInheritShorts,
            onShortsAutoplayBlocked = callbacks::onShortsAutoplayBlocked,
            onGallery = { urls, index -> GalleryActivity.open(context, urls, index) },
            onGoBack = callbacks::onBackRequested,
            onTransientUi = { transientUi.value = it },
        )
        val netTracer = NetTracer(context = bridge::diagnosticContext)
        val innertube = PageScript(INNERTUBE_JS, "Innertube")
        val swipeRefresh = SwipeRefreshLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setColorSchemeColors(YtRed.toArgb())
            setProgressViewOffset(true, 24, 96)
        }
        val downloadBridge = DownloadWebBridge(appContext = appContext, tabId = tabId)
        val webView = WebView(context).apply {
            BrowserDiagnostics.register(this, bridge::diagnosticContext)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            isFocusable = true
            isFocusableInTouchMode = true
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            setLayerType(WebView.LAYER_TYPE_HARDWARE, null)
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                cacheMode = WebSettings.LOAD_DEFAULT
                loadWithOverviewMode = true
                useWideViewPort = true
                loadsImagesAutomatically = true
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                mediaPlaybackRequiresUserGesture = false
                userAgentString = BrowserUserAgent.from(userAgentString)
                setSupportMultipleWindows(false)
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            }
            val initialDark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            syncTheme(this, initialDark)
            addJavascriptInterface(bridge, Bridge.NAME)
            extractor.installBrowserCapture(this) { bridge.pageOrigin().documentGeneration }
            coreScript.install(appContext, this)
            uiScript.install(appContext, this)
            navScript.install(appContext, this)
            netTracer.install(appContext, this)
            innertube.install(appContext, this)
            playerHookScript.install(appContext, this)
            dislikesScript.install(appContext, this)
            hideShortsScript.install(appContext, this)
            shortsAdsScript.install(appContext, this)
            subscribeButtonScript.install(appContext, this)
            shortsScript.install(appContext, this)
            shortsQualityScript.install(appContext, this)
            galleryScript.install(appContext, this)
            adsScript.install(appContext, this)
            downloadScript.install(appContext, this)
            downloadBridge.attach(this)
            webViewClient = BrowserWebViewClient(
                appContext = appContext,
                callbacks = callbacks,
                injector = injector,
                coreScript = coreScript,
                netTracer = netTracer,
                innertube = innertube,
                playerHook = playerHookScript,
                dislikes = dislikesScript,
                hideShorts = hideShortsScript,
                shortsAds = shortsAdsScript,
                subscribeButton = subscribeButtonScript,
                downloadScript = downloadScript,
                downloadBridge = downloadBridge,
                bridge = bridge,
                swipeRefresh = swipeRefresh,
            )
            webChromeClient = BrowserChromeClient(callbacks, onMicrophoneRequest)
        }

        swipeRefresh.addView(webView)
        swipeRefresh.setOnRefreshListener { onRefresh(webView) }

        return BrowserHost(swipeRefresh, webView, downloadBridge, transientUi.asStateFlow(), bridge::pageOrigin)
    }

    internal fun injectExtras(context: Context, webView: WebView) {
        shortsScript.inject(context, webView)
        shortsQualityScript.inject(context, webView)
        galleryScript.inject(context, webView)
        adsScript.inject(context, webView)
    }

    internal fun injectNavScript(context: Context, webView: WebView) {
        uiScript.inject(context, webView)
        navScript.inject(context, webView)
    }
}

data class BrowserHost(
    val container: SwipeRefreshLayout,
    val webView: WebView,
    val downloadBridge: DownloadWebBridge? = null,
    val transientUi: StateFlow<TransientUi> = MutableStateFlow(TransientUi()),
    val documentOrigin: () -> PageOrigin = { PageOrigin.HOST },
)

private class BrowserWebViewClient(
    private val appContext: Context,
    private val callbacks: WebViewCallbacks,
    private val injector: ExtensionInjector,
    private val coreScript: PageScript,
    private val netTracer: NetTracer,
    private val innertube: PageScript,
    private val playerHook: PageScript,
    private val dislikes: PageScript,
    private val hideShorts: PageScript,
    private val shortsAds: PageScript,
    private val subscribeButton: PageScript,
    private val downloadScript: PageScript,
    private val downloadBridge: DownloadWebBridge,
    private val bridge: Bridge,
    private val swipeRefresh: SwipeRefreshLayout,
) : WebViewClient() {
    private val login = LoginNavigation()
    private var lastContentUrl: String? = null
    private var pageOperation: DiagnosticOperation? = null
    private var loginOperation: DiagnosticOperation? = null
    private var loadingUrl: String? = null

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if (!request.isForMainFrame) return false
        val uri = request.url
        val url = uri.toString()
        if (uri.scheme.equals("intent", ignoreCase = true)) {
            return openExternal(view.context, uri)
        }
        if (!UrlPolicy.canLoad(url)) {
            return openExternal(view.context, uri)
        }
        val current = view.url
        if (UrlPolicy.isTrafficChallenge(current) || UrlPolicy.isTrafficChallenge(url)) return false
        if (login.keepInTab(current, url)) return false
        if (!current.isNullOrBlank()) {
            val nextKind = PageKind.of(url)
            if (nextKind == Constants.PAGE_SHORTS && callbacks.shouldInheritShorts()) return false
            if (nextKind != "unknown" && nextKind != PageKind.of(current)) {
                callbacks.onOpenTab(url)
                return true
            }
        }
        return false
    }

    /** Inject every page script in dependency order. */
    private fun injectAll(view: WebView) {
        if (!UrlPolicy.shouldInject(view.url)) return
        coreScript.inject(appContext, view)
        WebViewFactory.injectNavScript(appContext, view)
        netTracer.inject(appContext, view)
        innertube.inject(appContext, view)
        playerHook.inject(appContext, view)
        WebViewFactory.injectExtras(appContext, view)
        dislikes.inject(appContext, view)
        hideShorts.inject(appContext, view)
        shortsAds.inject(appContext, view)
        subscribeButton.inject(appContext, view)
        downloadBridge.stamp(view)
        downloadScript.inject(appContext, view)
        injector.inject(view)
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        updateRefreshEnabled(url)
        if (login.started(url)) {
            loginOperation?.finish(DiagnosticOutcome.SUPERSEDED, "login_restarted")
            loginOperation = AppLog.operation(AppLog.Category.BROWSER, "login", bridge.diagnosticContext().child())
            lastContentUrl?.let(callbacks::onLoginStarted)
        }
        if (UrlPolicy.shouldInject(url)) lastContentUrl = url
        bridge.onDocumentStarted()
        pageOperation?.finish(DiagnosticOutcome.SUPERSEDED, "document_replaced")
        loadingUrl = url
        pageOperation = AppLog.operation(AppLog.Category.BROWSER, "page_load", bridge.diagnosticContext(),
            DiagnosticRedaction.resource(url) + mapOf("page_kind" to PageKind.of(url), "login" to UrlPolicy.isLoginUrl(url)))
        AppLog.snapshot(AppLog.Category.BROWSER, bridge.diagnosticContext(), mapOf("phase" to "document_loading", "page_kind" to PageKind.of(url)))
        downloadBridge.bumpPage()
        if (UrlPolicy.shouldInject(url)) downloadBridge.stamp(view)
        if (!hasDocumentStartScript) injectAll(view)
        callbacks.onPageStarted(url)
        callbacks.onNavigationStateChanged(view.canGoBack())
    }

    override fun onPageFinished(view: WebView, url: String) {
        super.onPageFinished(view, url)
        if (url == loadingUrl && url == view.url) pageOperation?.finish(DiagnosticOutcome.SUCCESS, "document_finished")
        if (url == view.url && login.finished(url)) {
            callbacks.onLoginFinished()
            loginOperation?.finish(DiagnosticOutcome.SUCCESS, "login_navigation_finished")
            WebViewFactory.persistCookies()
        }
        if (UrlPolicy.shouldInject(url) && hasDocumentStartScript) {
            injector.inject(view)
            dislikes.inject(appContext, view)
            subscribeButton.inject(appContext, view)
            downloadBridge.stamp(view)
            downloadScript.inject(appContext, view)
        } else {
            injectAll(view)
        }
        val isDark = view.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        WebViewFactory.syncTheme(view, isDark)
        callbacks.onPageFinished(url)
        callbacks.onNavigationStateChanged(view.canGoBack())
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
        super.doUpdateVisitedHistory(view, url, isReload)
        AppLog.event(AppLog.Category.BROWSER, "spa_navigation", DiagnosticRedaction.resource(url) + mapOf("reload" to isReload, "page_kind" to PageKind.of(url)), context = bridge.diagnosticContext())
        // SPA navigations often skip full reloads; re-run inject for settings.
        updateRefreshEnabled(url)
        if (UrlPolicy.shouldInject(url)) {
            injector.inject(view)
            dislikes.inject(appContext, view)
            subscribeButton.inject(appContext, view)
            downloadBridge.bumpPage()
            downloadBridge.stamp(view)
            downloadScript.inject(appContext, view)
        }
        callbacks.onHistoryChanged(url)
        callbacks.onNavigationStateChanged(view.canGoBack())
    }

    private fun openExternal(context: Context, uri: Uri): Boolean {
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
            true
        } catch (_: ActivityNotFoundException) {
            externalLinkUnavailable(context)
            true
        } catch (_: SecurityException) {
            // An OEM-banned package.
            externalLinkUnavailable(context)
            true
        }
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (request.isForMainFrame) {
            pageOperation?.finish(DiagnosticOutcome.FAILURE, "main_document_error", fields = mapOf("error_code" to error.errorCode))
            loginOperation?.finish(DiagnosticOutcome.FAILURE, "main_document_error")
        }
        super.onReceivedError(view, request, error)
    }
    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
        if (request.isForMainFrame) pageOperation?.finish(DiagnosticOutcome.FAILURE, "main_document_http_error", fields = mapOf("status" to response.statusCode))
        super.onReceivedHttpError(view, request, response)
    }
    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        val mainDocument = error.url != null && (error.url == loadingUrl || error.url == view.url)
        AppLog.event(AppLog.Category.BROWSER, "ssl_error", DiagnosticRedaction.resource(error.url.orEmpty()) +
            mapOf("error_code" to error.primaryError, "main_document" to true.takeIf { mainDocument }),
            critical = true, context = bridge.diagnosticContext())
        if (mainDocument) {
            pageOperation?.finish(DiagnosticOutcome.FAILURE, "main_document_ssl_error")
            loginOperation?.finish(DiagnosticOutcome.FAILURE, "main_document_ssl_error")
        }
        super.onReceivedSslError(view, handler, error)
    }
    @androidx.annotation.RequiresApi(26)
    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        AppLog.event(AppLog.Category.BROWSER, "renderer_exit", mapOf("crashed" to detail.didCrash(), "priority" to detail.rendererPriorityAtExit()), critical = true, context = bridge.diagnosticContext())
        pageOperation?.finish(DiagnosticOutcome.FAILURE, "renderer_exit")
        loginOperation?.finish(DiagnosticOutcome.FAILURE, "renderer_exit")
        return super.onRenderProcessGone(view, detail)
    }

    private fun externalLinkUnavailable(context: Context) {
        // Surface the dead tap; loading in-page would bypass the allowlist.
        Toast.makeText(
            context,
            R.string.application_not_found,
            Toast.LENGTH_SHORT,
        ).show()
    }

    /** Pull-to-refresh only on pages without conflicting vertical gestures. */
    private fun updateRefreshEnabled(url: String) {
        swipeRefresh.isEnabled = PageKind.of(url) in REFRESH_KINDS
    }

    private companion object {
        val hasDocumentStartScript =
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        val REFRESH_KINDS = setOf(
            Constants.PAGE_HOME,
            Constants.PAGE_SUBSCRIPTIONS,
            Constants.PAGE_LIBRARY,
            "@",
        )
    }
}

private class BrowserChromeClient(
    private val callbacks: WebViewCallbacks,
    private val onMicrophoneRequest: ((onResult: (Boolean) -> Unit) -> Unit)?,
) : WebChromeClient() {

    override fun onProgressChanged(view: WebView, newProgress: Int) {
        callbacks.onProgressChanged(newProgress)
        super.onProgressChanged(view, newProgress)
    }

    override fun onPermissionRequest(request: PermissionRequest) {
        val audio = PermissionRequest.RESOURCE_AUDIO_CAPTURE
        val voiceSearch = callbacks.isActiveTab() && request.resources.singleOrNull() == audio &&
            UrlPolicy.shouldInject(request.origin.toString())
        if (onMicrophoneRequest == null || !voiceSearch) { request.deny(); return }
        onMicrophoneRequest { granted -> if (granted) request.grant(arrayOf(audio)) else request.deny() }
    }

    override fun getDefaultVideoPoster(): Bitmap = createBitmap(1, 1)
}
