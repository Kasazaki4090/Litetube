(function () {
  if (window.__liteWatchReload) return;
  window.__liteWatchReload = true;

  // How long to wait after a watch page opens before deciding the initial render is bad,
  // and how long after that decision to actually reload. The whole thing runs at most ONCE
  // per unique video (guarded via sessionStorage), so it can never loop forever.
  var CHECK_DELAY = 1800;
  var RELOAD_AFTER_DECISION = 700;

  function isWatch() {
    return /\/watch\//.test(location.pathname) || !!document.querySelector('#movie_player');
  }

  function videoId() {
    try {
      var p = document.querySelector('#movie_player');
      var d = p && typeof p.getVideoData === 'function' ? p.getVideoData() : null;
      if (d && d.video_id) return String(d.video_id);
    } catch (e) {}
    var m = /[?&]v=([A-Za-z0-9_-]{6,})/.exec(location.href);
    return m ? m[1] : null;
  }

  // A healthy watch page has its action bar (like/dislike/share/save buttons) hydrated.
  function renderedOK() {
    if (document.querySelector('.slim_video_action_bar_renderer_button')) return true;
    if (document.querySelector('ytm-subscribe-button-renderer, [class*="subscribe"]')) return true;
    var labeled = document.querySelectorAll('[aria-label]');
    for (var i = 0; i < labeled.length; i++) {
      var t = (labeled[i].getAttribute('aria-label') || '').toLowerCase();
      if (/like|dislike|share|共有/.test(t)) return true;
    }
    return false;
  }

  function check() {
    if (!isWatch()) return;
    var vid = videoId();
    if (!vid) return;

    if (renderedOK()) { sessionStorage.removeItem('lite_wreload_done_' + vid); return; }

    // Initial render looks broken. Reload once for this specific video, then stop trying.
    try {
      if (sessionStorage.getItem('lite_wreload_done_' + vid)) return;
      sessionStorage.setItem('lite_wreload_done_' + vid, '1');
    } catch (e) {}

    setTimeout(function () { location.reload(); }, RELOAD_AFTER_DECISION);
  }

  function schedule() {
    clearTimeout(window.__liteWCheckT);
    window.__liteWCheckT = setTimeout(check, CHECK_DELAY);
  }

  window.addEventListener('yt-navigate-finish', schedule, true);
  if (document.readyState === 'complete' || document.readyState === 'interactive') schedule();
  else window.addEventListener('DOMContentLoaded', schedule);
})();
