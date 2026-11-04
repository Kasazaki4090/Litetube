(() => {
  'use strict';
  if (window.__innertube) return;
  window.__innertube = true;
  const originalFetch = window.fetch.bind(window);
  const fields = ['INNERTUBE_CONTEXT', 'INNERTUBE_API_KEY', 'SESSION_INDEX', 'DATASYNC_ID',
    'DELEGATED_SESSION_ID', 'USER_SESSION_ID', 'VISITOR_DATA', 'LOGGED_IN', 'IS_PREMIUM',
    'PLAYER_JS_URL', 'WEB_PLAYER_CONTEXT_CONFIGS', 'EXPERIMENT_FLAGS', 'EXPERIMENTS_FORCED_FLAGS', 'EVENT_ID',
    'INNERTUBE_CLIENT_VERSION'];
  function extractPoTokenFromPlayer(player) {
    try {
      const formats = [
        ...(player?.streamingData?.formats || []),
        ...(player?.streamingData?.adaptiveFormats || []),
      ];
      for (const f of formats) {
        const u = f?.url;
        if (typeof u === 'string' && u.includes('pot=')) {
          const m = u.match(/[?&]pot=([^&]+)/);
          if (m && m[1]) return decodeURIComponent(m[1]);
        }
      }
    } catch {}
    return null;
  }
  function configuration() {
    const source = window.ytcfg?.data_;
    if (!source) return null;
    const result = {};
    for (const name of fields) if (source[name] != null) result[name] = source[name];
    if (!result.INNERTUBE_CLIENT_VERSION) {
      result.INNERTUBE_CLIENT_VERSION = source.INNERTUBE_CONTEXT_CLIENT_VERSION
        || source.INNERTUBE_CONTEXT?.client?.clientVersion;
    }
    if (!result.PLAYER_JS_URL) {
      result.PLAYER_JS_URL = Object.values(result.WEB_PLAYER_CONTEXT_CONFIGS || {}).find(c => c?.jsUrl)?.jsUrl;
    }
    result._IS_LIVE = window.ytInitialPlayerResponse?.videoDetails?.isLiveContent === true;
    result._VIDEO_ID = window.ytInitialPlayerResponse?.videoDetails?.videoId;
    const pot = extractPoTokenFromPlayer(window.ytInitialPlayerResponse);
    if (pot) result._PO_TOKEN = pot;
    const logo = window.ytInitialData?.topbar?.desktopTopbarRenderer?.logo?.topbarLogoRenderer;
    if (logo?.iconImage?.iconType === 'YOUTUBE_PREMIUM_LOGO' || /premium/i.test(logo?.tooltip || '')) result.IS_PREMIUM = true;
    return result;
  }
  function publish(message) {
    try {
      const bridge = window.Bridge;
      const generation = (bridge && typeof bridge.currentDocumentGeneration === 'function')
        ? bridge.currentDocumentGeneration()
        : 0;
      const capture = window.NativePlayerCapture;
      if (!capture || typeof capture.postMessage !== 'function') return;
      const session = (bridge && typeof bridge.currentExtractionSession === 'function')
        ? bridge.currentExtractionSession()
        : '';
      const value = JSON.stringify({ generation, session, ...message });
      if (value.length <= 2 * 1024 * 1024) capture.postMessage(value);
    } catch {}
  }
  function checkAndPostTimedtext() {
    try {
      const capture = window.NativePlayerCapture;
      if (!capture || typeof capture.postTimedtextUrl !== 'function') return;
      const entries = window.performance?.getEntriesByType?.('resource') || [];
      for (let i = entries.length - 1; i >= 0; i--) {
        const u = entries[i].name;
        if (typeof u === 'string' && u.includes('/api/timedtext') && u.includes('pot=')) {
          capture.postTimedtextUrl(u);
        }
      }
    } catch {}
  }
  function triggerCaptions() {
    try {
      const p = document.getElementById('movie_player');
      if (p) {
        p.loadModule?.('captions');
        const tracks = p.getOption?.('captions', 'tracklist');
        if (tracks && tracks.length > 0) {
          p.setOption?.('captions', 'track', tracks[0]);
        }
      }
    } catch {}
    checkAndPostTimedtext();
  }
  let lastPublishedInitialId = null;
  function captureInitialPlayerResponse() {
    try {
      const player = window.ytInitialPlayerResponse;
      const videoId = player?.videoDetails?.videoId;
      if (!videoId || videoId === lastPublishedInitialId) return;
      if (player?.playabilityStatus?.status !== 'OK' || !player?.streamingData) return;
      lastPublishedInitialId = videoId;
      const config = configuration() || {};
      config._IS_LIVE = player.videoDetails?.isLiveContent === true;
      config._VIDEO_ID = videoId;
      publish({
        request: JSON.stringify({ videoId, context: { client: { clientName: 'MWEB' } } }),
        response: JSON.stringify(player),
        configuration: config,
      });
    } catch {}
  }
  function captureConfiguration() {
    publish({ configuration: configuration() });
    captureInitialPlayerResponse();
    triggerCaptions();
    [200, 600, 1500, 3000].forEach(delay => setTimeout(triggerCaptions, delay));
  }
  if (!window.__xhrTimedtextHooked) {
    window.__xhrTimedtextHooked = true;
    const origOpen = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function(method, url) {
      try {
        if (typeof url === 'string' && url.includes('/api/timedtext') && url.includes('pot=')) {
          window.NativePlayerCapture?.postTimedtextUrl?.(url);
        }
      } catch {}
      return origOpen.apply(this, arguments);
    };
  }
  captureConfiguration();
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', captureConfiguration, { once: true });
  window.addEventListener('yt-navigate-finish', captureConfiguration);
  window.fetch = function(input, init) {
    const url = typeof input === 'string' ? input : input?.url || '';
    const method = String(init?.method || input?.method || 'GET').toUpperCase();
    if (method !== 'POST' || !url.includes('/youtubei/v1/player')) return originalFetch(input, init);
    // Start the original immediately; observe clones without replacing browser responses.
    const raw = init?.body;
    const requestText = typeof raw === 'string' ? Promise.resolve(raw)
      : raw instanceof Uint8Array || raw instanceof ArrayBuffer ? Promise.resolve(new TextDecoder().decode(raw))
      : input?.clone ? input.clone().text().catch(() => null) : Promise.resolve(null);
    captureConfiguration();
    return originalFetch(input, init).then(response => {
      requestText.then(text => {
        if (!text) return;
        if (response.status === 200) response.clone().text().then(body => {
          try {
            const player = JSON.parse(body);
            const config = configuration() || {};
            if (player?.videoDetails?.videoId) {
              config._IS_LIVE = player.videoDetails.isLiveContent === true;
              config._VIDEO_ID = player.videoDetails.videoId;
            }
            const pot = extractPoTokenFromPlayer(player);
            if (pot) config._PO_TOKEN = pot;
            const session = window.Bridge && typeof window.Bridge.currentExtractionSession === 'function'
              ? window.Bridge.currentExtractionSession()
              : '';
            publish({ session, request: text, response: body, configuration: config });
          } catch (_) {}
        }).catch(() => {});
      }).catch(() => {});
      return response;
    });
  };
})();
