(function() {
  if (typeof window.__liteSubscribeScan === 'function') {
    window.__liteSubscribeScan();
    return;
  }
  if (window.__liteSubscribeBtn) return;
  window.__liteSubscribeBtn = true;

  function injectStyles() {
    if (document.getElementById('lite-subscribe-style')) return;
    var root = document.head || document.documentElement;
    if (!root) return;
    var style = document.createElement('style');
    style.id = 'lite-subscribe-style';
    style.textContent = '' +
      '.channel-item-badge,' +
      '.ytm-channel-item-renderer-badge,' +
      'ytm-channel-item-renderer .badge,' +
      'ytm-compact-channel-renderer .badge,' +
      '.subscription-item-badge,' +
      '[class*="channel-item-badge"],' +
      '[class*="subscription-badge"],' +
      'ytm-badge-and-byline-renderer .badge,' +
      'ytm-slim-owner-renderer .slim-owner-subtitle,' +
      'ytm-video-owner-renderer .owner-subscriber-count,' +
      '#owner-sub-count {' +
      '  display: none !important;' +
      '}' +
      'ytm-channel-item-renderer ytm-subscribe-button-renderer,' +
      'ytm-compact-channel-renderer ytm-subscribe-button-renderer,' +
      'ytm-channel-item-renderer .subscribe-button,' +
      'ytm-compact-channel-renderer .subscribe-button,' +
      'ytm-video-owner-renderer ytm-subscribe-button-renderer,' +
      'ytm-video-owner-renderer [class*="subscribe-button"],' +
      'ytm-video-owner-renderer .video-owner-subscriber-container,' +
      'ytm-slim-owner-renderer ytm-subscribe-button-renderer,' +
      'ytm-slim-owner-renderer [class*="subscribe-button"] {' +
      '  display: inline-flex !important;' +
      '}';
    root.appendChild(style);
  }

  function fixOwnerSection(owner) {
    if (!owner) return;

    // 1. Hide explicit badge elements
    var badges = owner.querySelectorAll(
      '.badge, [class*="badge"], .owner-sub-count, .slim-owner-subtitle, #owner-sub-count, [class*="subscriber-count"], [class*="owner-subscriber"]'
    );
    for (var i = 0; i < badges.length; i++) {
      var b = badges[i];
      if (b.tagName !== 'YTM-SUBSCRIBE-BUTTON-RENDERER' && !b.querySelector('ytm-subscribe-button-renderer')) {
        b.style.setProperty('display', 'none', 'important');
      }
    }

    // 2. Handle leaf text nodes holding pure numbers ("0", "166", "1.2K")
    var leaves = owner.querySelectorAll('span, div, yt-formatted-string, a');
    for (var j = 0; j < leaves.length; j++) {
      var leaf = leaves[j];
      if (leaf.children.length === 0) {
        var text = (leaf.textContent || '').trim();
        if (/^\d+([.,]\d+)?[KMB]?$/i.test(text)) {
          var parentBtn = leaf.closest('button, ytm-subscribe-button-renderer, [class*="subscribe-button"], .yt-spec-button-shape-next');
          if (!parentBtn) {
            leaf.style.setProperty('display', 'none', 'important');
          } else {
            var btnText = (parentBtn.textContent || '').trim();
            if (/^\d+([.,]\d+)?[KMB]?$/i.test(btnText)) {
              var targetSpan = parentBtn.querySelector('.ytSpecButtonShapeNextButtonTextContent') || leaf;
              targetSpan.textContent = '已订阅';
            }
          }
        }
      }
    }
  }

  function getOwnerContainers() {
    return document.querySelectorAll(
      'ytm-video-owner-renderer, ytm-slim-owner-renderer, [class*="video-owner"], [class*="slim-owner"], ytm-channel-item-renderer, ytm-compact-channel-renderer, .subscription-item'
    );
  }

  function scan() {
    injectStyles();
    var owners = getOwnerContainers();
    for (var i = 0; i < owners.length; i++) {
      fixOwnerSection(owners[i]);
    }
  }

  window.__liteSubscribeScan = scan;

  var scheduled = false;
  function schedule() {
    if (scheduled) return;
    scheduled = true;
    requestAnimationFrame(function() {
      scheduled = false;
      scan();
    });
  }

  var observer = new MutationObserver(schedule);
  function observeRoot() {
    var root = document.documentElement || document.body;
    if (root) {
      observer.observe(root, { childList: true, subtree: true, characterData: true });
    }
  }
  observeRoot();

  if (window.Lite && typeof window.Lite.module === 'function') {
    window.Lite.module('subscribe_button', function() {
      schedule();
      return true;
    });
  }

  window.addEventListener('yt-navigate-finish', schedule, true);
  window.addEventListener('yt-page-data-updated', schedule, true);
  window.addEventListener('popstate', schedule, true);
  if (document.readyState === 'complete' || document.readyState === 'interactive') {
    scan();
  } else {
    window.addEventListener('DOMContentLoaded', function() {
      observeRoot();
      scan();
    });
  }
})();
