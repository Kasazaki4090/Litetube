(function () {
  if (window.__liteSubscribeBtn) return;
  window.__liteSubscribeBtn = true;

  var HIDDEN_ATTR = 'data-lite-hidden-subcount';

  function isWatchPage() {
    return /\/watch/.test(location.pathname) || !!document.querySelector('#movie_player');
  }

  function classNameOf(el) {
    var c = el.className;
    return typeof c === 'string' ? c : '';
  }

  // Pure compact number, including "0": "0", "166", "1.2K" ...
  function isBareNumber(text) {
    var t = (text || '').trim();
    if (!t) return false;
    return /^[\d.,]+\s*[KMB]?\s*(subscribers|new videos|videos|チャンネル登録者)?$/i.test(t);
  }

  // Never hide elements that clearly hold other meaningful counts.
  function isProtected(el) {
    var cls = classNameOf(el).toLowerCase();
    if (/view-count|video-count|comment-count|like|dislike|watch-time/.test(cls)) return true;
    var al = (el.getAttribute('aria-label') || '').toLowerCase();
    if (/subscrib|channeldangyou/.test(al) || /チャンネル登録/.test(al)) return true;
    return false;
  }

  function isInsideButton(el) {
    var n = el;
    while (n && n !== document.body) {
      if (n.tagName === 'BUTTON') return true;
      if (/button|subscribe/i.test(classNameOf(n))) return true;
      n = n.parentElement;
    }
    return false;
  }

  function hideEl(el) {
    el.style.display = 'none';
    el.setAttribute(HIDDEN_ATTR, '1');
  }

  // Containers that may show a numeric "new videos" / subscriber badge next to an avatar.
  var BADGE_HOSTS = [
    'ytm-channel-item-renderer',
    'ytm-compact-channel-renderer',
    'ytm-slim-owner-renderer',
    'ytm-video-owner-renderer',
    '[class*="slim-owner"]',
    '[class*="video-owner"]',
    '.subscription-item'
  ].join(',');

  // Hide numeric count badges (including "0") next to channel avatars, on all pages.
  function hideBadges() {
    var hosts = document.querySelectorAll(BADGE_HOSTS);
    for (var h = 0; h < hosts.length; h++) {
      var host = hosts[h];
      var nodes = host.querySelectorAll('span, div, a, yt-formatted-string');
      for (var i = 0; i < nodes.length; i++) {
        var el = nodes[i];
        if (el.getAttribute(HIDDEN_ATTR) || isProtected(el)) continue;

        var cls = classNameOf(el);
        // Explicit count/badge elements.
        if (/subscriber|sub-count|owner-sub|badge|new-video|unwatched/i.test(cls)) { hideEl(el); continue; }

        // Leaf holding a bare number (e.g. "0", "166") that is not part of a button.
        if (el.children.length === 0 && isBareNumber(el.textContent) && !isInsideButton(el)) hideEl(el);
      }
    }
  }

  function getOwnerSections() {
    var out = [];
    document.querySelectorAll(BADGE_HOSTS).forEach(function (el) {
      if (/owner/i.test(el.tagName.toLowerCase()) || /owner/.test(classNameOf(el))) out.push(el);
    });
    return out;
  }

  function findNativeSubscribeBtn(owner) {
    var candidates = owner.querySelectorAll(
      'ytm-subscribe-button-renderer, [class*="subscribe"], a[aria-label], button[aria-label], .yt-spec-button-shape-next'
    );
    for (var i = 0; i < candidates.length; i++) {
      var el = candidates[i];
      var al = (el.getAttribute('aria-label') || '').toLowerCase();
      if (/subscrib/i.test(classNameOf(el)) || /subscrib|channeldangyou/.test(al) || /チャンネル登録/.test(al)) return el;
    }
    return null;
  }

  function revealNative(owner) {
    var btn = findNativeSubscribeBtn(owner);
    if (!btn) return false;
    btn.style.display = 'inline-flex';
    var wrap = btn.closest ? btn.closest('ytm-subscribe-button-renderer') : null;
    if (wrap && wrap !== btn) wrap.style.display = 'inline-flex';
    return true;
  }

  function getChannelUrl(owner) {
    var link = owner.querySelector('a[href*="/@"], a[href*="channel/"], ytm-channel-avatar-renderer a');
    return link ? (link.getAttribute('href') || '') : '';
  }

  // Fallback only when YouTube does not expose its own subscribe control on the watch page.
  function injectFallback(owner) {
    if (owner.querySelector('.lite-subs-btn')) return;
    var url = getChannelUrl(owner);
    if (!url) return;
    var btn = document.createElement('a');
    btn.className = 'lite-subs-btn';
    btn.href = url;
    btn.textContent = 'Subscribe';
    btn.style.cssText =
      'display:inline-flex;align-items:center;padding:4px 12px;border-radius:20px;font-size:13px;' +
      'font-weight:500;text-decoration:none;color:#fff;background:#ff0000;margin-left:8px;white-space:nowrap;';
    var anchor = owner.querySelector('ytm-channel-avatar-renderer') || owner.firstElementChild;
    if (anchor && anchor.nextSibling) owner.insertBefore(btn, anchor.nextSibling);
    else owner.appendChild(btn);
  }

  function apply() {
    // Badge hiding is global so "0" counts disappear on feed and watch pages alike.
    hideBadges();

    if (!isWatchPage()) return;
    var owners = getOwnerSections();
    for (var i = 0; i < owners.length; i++) {
      if (!revealNative(owners[i])) injectFallback(owners[i]);
    }
  }

  // Owner sections / feed items render asynchronously, so re-apply on DOM changes.
  var timer = null;
  function schedule() {
    if (timer) return;
    timer = setTimeout(function () { timer = null; apply(); }, 250);
  }

  window.addEventListener('yt-navigate-finish', apply, true);
  window.addEventListener('popstate', apply, true);

  var observer = new MutationObserver(schedule);
  function start() {
    if (document.body) observer.observe(document.body, { childList: true, subtree: true });
    setTimeout(apply, 400);
  }

  if (document.readyState === 'complete' || document.readyState === 'interactive') start();
  else window.addEventListener('DOMContentLoaded', start);
})();
