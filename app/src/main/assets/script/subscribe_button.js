(function () {
  if (window.__liteSubscribeBtn) return;
  window.__liteSubscribeBtn = true;

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

    // 3. Ensure subscribe / subscribed button is visible
    var subRenderer = owner.querySelector('ytm-subscribe-button-renderer, [class*="subscribe-button"]');
    if (subRenderer) {
      subRenderer.style.setProperty('display', 'inline-flex', 'important');
      var btn = subRenderer.querySelector('button, .yt-spec-button-shape-next, a');
      if (btn) {
        btn.style.setProperty('display', 'inline-flex', 'important');
        var t = (btn.textContent || '').trim();
        if (!t || /^\d+([.,]\d+)?[KMB]?$/i.test(t)) {
          var textContentSpan = btn.querySelector('.ytSpecButtonShapeNextButtonTextContent') || btn;
          textContentSpan.textContent = '已订阅';
        }
      }
    }
  }

  function apply() {
    var targets = document.querySelectorAll(
      'ytm-slim-owner-renderer, ytm-video-owner-renderer, ytm-channel-item-renderer, ytm-compact-channel-renderer, .subscription-item'
    );
    for (var k = 0; k < targets.length; k++) {
      fixOwnerSection(targets[k]);
    }
  }

  var timer = null;
  function schedule() {
    if (timer) return;
    timer = setTimeout(function () {
      timer = null;
      apply();
    }, 150);
  }

  window.addEventListener('yt-navigate-finish', schedule, true);
  window.addEventListener('popstate', schedule, true);

  var observer = new MutationObserver(schedule);
  function start() {
    var root = document.body || document.documentElement;
    if (root) {
      try {
        observer.observe(root, { childList: true, subtree: true });
      } catch (e) {}
    }
    apply();
  }

  if (document.readyState === 'complete' || document.readyState === 'interactive') {
    start();
  } else {
    window.addEventListener('DOMContentLoaded', start);
  }
})();
