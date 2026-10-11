/**
 * dsh-mobile-shell.js — injected into the agent WebView on every page load.
 *
 * Adapts the desktop-focused DeepSeek Harness three-column layout
 * (sidebar / chat / file manager) and the OpenCode dashboard to the screen it
 * is actually on:
 *
 *  NARROW (< 840 CSS px — phones, small tablets):
 *    - Sidebars collapse into a swipeable sliding drawer (hamburger toggle).
 *    - The file manager moves into a bottom tab next to Chat.
 *    - The text composer is pinned to the bottom, above the keyboard.
 *
 *  WIDE (>= 840 CSS px — large tablets):
 *    - The page keeps its own multi-column layout. Collapsing a 1400px
 *      sidebar into a drawer that must be summoned every time is strictly
 *      worse than showing it, so the transformation is undone.
 *    - The conversation column is capped and centred, because a transcript
 *      stretched across a tablet has no usable line length.
 *    - No injected chrome: no hamburger, no bottom tab bar. The page's own
 *      navigation is already visible.
 *
 * On both sizes:
 *  - Tool-execution prompts ("Do you want to run…?", "Allow file
 *    modification?") are intercepted from the DOM and forwarded to the
 *    native Android Bottom Sheet via the `DshBridge` JS interface instead
 *    of rendering as terminal text.
 *  - Raw <pre>/xterm terminal nodes are hidden — the user never sees a CLI.
 *
 * The 840px threshold is the same breakpoint as the native side panel
 * (AdaptiveLayout.useSidePanel). If you change one, change both: a tablet
 * showing a persistent native panel must not also be showing an injected
 * drawer, or the same navigation appears twice.
 *
 * The WebView injects this as a post-load script; it is idempotent and
 * re-applies on SPA route changes via MutationObserver.
 */
(function () {
  'use strict';
  if (window.__dshMobileShellInstalled) return;
  window.__dshMobileShellInstalled = true;

  var SHELL_ID = 'dsh-mobile-shell';
  var DRAWER_ID = 'dsh-mobile-drawer';
  var TABBAR_ID = 'dsh-mobile-tabbar';
  var TOGGLE_ID = 'dsh-mobile-nav-toggle';

  /*
   * Keep in sync with AdaptiveLayout.useSidePanel.
   *
   * That rule is two-axis — `widthClass == Expanded && heightClass != Compact`
   * — and the height half is load-bearing: a phone in landscape is routinely
   * wider than 840dp (891x411dp is an everyday viewport), and a width-only
   * query put such a phone into the tablet presentation. The page then deleted
   * its own hamburger, drawer and Chat/Files tab bar, leaving a landscape phone
   * with no way to reach Files at all.
   *
   * The half-open case is closed too. On the side-panel path the WebView is
   * (window - 360dp panel - 1dp rule) wide, so the page only reaches this
   * breakpoint at window >= 1201dp; between 840dp and 1201dp the native panel
   * and the page's own hamburger were both on screen at once.
   */
  var WIDE_QUERY = '(min-width: 840px) and (min-height: 480px)';

  /*
   * Native owns the presentation decision; this page only obeys.
   *
   * An earlier revision derived "wide" here from the WebView's own media query
   * and let it decide whether to keep the hamburger and the Chat/Files tab bar.
   * That is wrong twice over:
   *
   *  1. The page's viewport is not the window. On the side-panel path it is
   *     (window - 360dp panel - 1dp rule), so between 840dp and 1201dp the
   *     native panel and the page's own hamburger were both on screen.
   *  2. A media query cannot see a landscape phone at all: 891x411dp is wider
   *     than 840dp, so the page deleted its own navigation and left the user
   *     with no route to Files.
   *
   * DshMobileShell now calls `__dshSetNativeChrome(useSidePanel)` on every load
   * and on every layout change. Until it does, `isWide()` falls back to the
   * two-axis query so the page still degrades sanely if the call is ever lost.
   */
  var nativeChromeWide = null;

  function isWide() {
    if (nativeChromeWide !== null) return nativeChromeWide;
    // Measured in JS, never with matchMedia.
    //
    // This page collapses the document root (html{height:0} behind the shell's
    // own layout), and a WebView reports media-query heights against that
    // collapsed box rather than the window. Verified on-device at a 1923x1202dp
    // window: innerHeight was 1157 and matchMedia('(min-height: 1px)').matches
    // was FALSE, while matchMedia('(max-height: 1px)') was true. So any
    // min-height query is permanently unsatisfiable here, and a two-axis
    // media query silently never matches - the wide presentation could not
    // activate no matter how large the window became.
    return window.innerWidth >= 840 && window.innerHeight >= 480;
  }

  /** Called from native. `wide` mirrors AdaptiveLayout.useSidePanel. */
  window.__dshSetNativeChrome = function (wide) {
    nativeChromeWide = !!wide;
    document.body.classList.toggle('dsh-native-wide', nativeChromeWide);
    apply();
  };

  /* ------------------------------------------------------------------ */
  /* 1. Stylesheet: responsive shell transformation                      */
  /* ------------------------------------------------------------------ */
  function ensureStyles() {
    if (document.getElementById(SHELL_ID)) return;
    var css = [
      ':root{--dsh-mobile-bar:#0A0D12;--dsh-mobile-accent:#6FB4FF;',
      '  --dsh-mobile-edge:rgba(255,255,255,.12);}',
      // Hide raw terminal surfaces entirely.
      '.xterm,.xterm-screen,.terminal-container,pre.terminal-output,[data-terminal-root]{display:none!important;}',

      /* ---- narrow: drawer + tabs ---- */
      '.dsh-sidebar,.sidebar,[data-panel="sidebar"],aside:first-of-type{',
      '  position:fixed!important;top:0;bottom:0;left:0;width:min(86vw,340px)!important;',
      '  transform:translateX(-105%);transition:transform .24s ease;z-index:60;',
      '  box-shadow:0 0 32px rgba(0,0,0,.5);overflow-y:auto;}',
      'body.dsh-drawer-open .dsh-sidebar,body.dsh-drawer-open .sidebar,',
      'body.dsh-drawer-open [data-panel="sidebar"]{transform:none!important;}',
      '.dsh-main,[data-panel="main"],main{margin-left:0!important;width:100%!important;}',
      '[data-panel="files"],.dsh-file-manager,.file-explorer{display:none!important;}',
      'body.dsh-tab-files [data-panel="files"],body.dsh-tab-files .dsh-file-manager,',
      'body.dsh-tab-files .file-explorer{display:block!important;position:static!important;',
      '  width:100%!important;transform:none!important;}',
      'body.dsh-tab-files [data-panel="chat"],body.dsh-tab-files .dsh-chat{display:none!important;}',
      '#' + TABBAR_ID + '{position:fixed;left:0;right:0;bottom:0;z-index:55;display:flex;',
      '  background:var(--dsh-mobile-bar);border-top:1px solid var(--dsh-mobile-edge);',
      '  padding-bottom:env(safe-area-inset-bottom);}',
      '#' + TABBAR_ID + ' button{flex:1;padding:12px 0;background:none;border:none;',
      '  color:#9AA6B6;font-size:13px;}',
      '#' + TABBAR_ID + ' button.active{color:var(--dsh-mobile-accent);font-weight:600;}',
      '#' + TOGGLE_ID + '{position:fixed;top:12px;left:12px;z-index:70;width:44px;height:44px;',
      '  border-radius:12px;border:1px solid var(--dsh-mobile-edge);',
      '  background:rgba(10,13,18,.82);backdrop-filter:blur(8px);',
      '  color:#fff;font-size:20px;}',
      '#' + DRAWER_ID + '-scrim{position:fixed;inset:0;background:rgba(0,0,0,.45);',
      '  z-index:59;display:none;}',
      'body.dsh-drawer-open #' + DRAWER_ID + '-scrim{display:block;}',

      // Composer pinned above the keyboard on every size. The extra bottom
      // padding fixes a real defect: the field's floating label was clipped
      // by the pinned container's own edge.
      '.dsh-composer,[data-composer],.chat-input-container{',
      '  position:sticky!important;bottom:0;z-index:40;',
      '  padding-bottom:calc(env(safe-area-inset-bottom) + 8px);}',

      /* ---- wide: undo the collapse, cap the reading column ---- */
      // Deliberately NOT a @media block. The height half of that query can
      // never match in this WebView (see isWide), so a media-keyed rule would
      // look correct in a desktop browser and never fire on the device.
      /*
       * Same rules, driven by native instead of by this page's width. The
       * side-panel path leaves the WebView narrower than 840dp, so the media
       * query above would keep the page collapsed while native had already
       * removed the hamburger — navigation with no way to reach it.
       */
      'body.dsh-native-wide .dsh-sidebar,body.dsh-native-wide .sidebar,',
      'body.dsh-native-wide [data-panel="sidebar"],',
      'body.dsh-native-wide aside:first-of-type{',
      '  position:static!important;transform:none!important;width:auto!important;',
      '  box-shadow:none!important;}',
      'body.dsh-native-wide [data-panel="files"],body.dsh-native-wide .dsh-file-manager,',
      'body.dsh-native-wide .file-explorer{display:block!important;position:static!important;',
      '  width:auto!important;}',
      'body.dsh-native-wide .dsh-main,body.dsh-native-wide [data-panel="main"],',
      'body.dsh-native-wide main{margin:0 auto!important;max-width:1100px!important;}',
      'body.dsh-native-wide .dsh-composer,body.dsh-native-wide [data-composer],',
      'body.dsh-native-wide .chat-input-container{position:static!important;}',
    ].join('\n');
    var style = document.createElement('style');
    style.id = SHELL_ID;
    style.textContent = css;
    document.head.appendChild(style);
  }

  /* ------------------------------------------------------------------ */
  /* 2. Chrome: hamburger toggle + Chat/Files tab bar                   */
  /* ------------------------------------------------------------------ */
  function ensureChrome() {
    var toggle = document.getElementById(TOGGLE_ID);
    var bar = document.getElementById(TABBAR_ID);
    var scrim = document.getElementById(DRAWER_ID + '-scrim');

    // On a wide screen the page's own navigation is already on screen, so
    // the injected chrome is removed rather than merely hidden - leaving it
    // in the DOM would keep duplicate navigation reachable by keyboard.
    if (isWide()) {
      [toggle, bar, scrim].forEach(function (el) {
        if (el && el.parentNode) el.parentNode.removeChild(el);
      });
      document.body.classList.remove('dsh-drawer-open', 'dsh-tab-files');
      return;
    }

    if (!toggle) {
      toggle = document.createElement('button');
      toggle.id = TOGGLE_ID;
      toggle.textContent = '☰';
      toggle.setAttribute('aria-label', 'Open navigation');
      toggle.addEventListener('click', function () {
        document.body.classList.toggle('dsh-drawer-open');
      });
      document.body.appendChild(toggle);

      scrim = document.createElement('div');
      scrim.id = DRAWER_ID + '-scrim';
      scrim.addEventListener('click', function () {
        document.body.classList.remove('dsh-drawer-open');
      });
      document.body.appendChild(scrim);
    }
    if (!bar) {
      bar = document.createElement('div');
      bar.id = TABBAR_ID;
      var chatBtn = document.createElement('button');
      chatBtn.textContent = '💬 Chat';
      chatBtn.className = 'active';
      var filesBtn = document.createElement('button');
      filesBtn.textContent = '📁 Files';
      chatBtn.addEventListener('click', function () {
        document.body.classList.remove('dsh-tab-files');
        chatBtn.className = 'active';
        filesBtn.className = '';
      });
      filesBtn.addEventListener('click', function () {
        document.body.classList.add('dsh-tab-files');
        filesBtn.className = 'active';
        chatBtn.className = '';
      });
      bar.appendChild(chatBtn);
      bar.appendChild(filesBtn);
      document.body.appendChild(bar);
    }
  }

  /* ------------------------------------------------------------------ */
  /* 3. Approval intercept: terminal prompt -> native Bottom Sheet       */
  /* ------------------------------------------------------------------ */
  var APPROVAL_RE = /(do you want to run|allow .*command|allow file modification|overwrite .*?\?|proceed\?|\[y\/n\])/i;
  var seenPrompts = new Set();

  function scanForApprovalPrompts(root) {
    if (!window.DshBridge || !DshBridge.postApprovalRequest) return;
    var walker = document.createTreeWalker(root || document.body, NodeFilter.SHOW_TEXT);
    var node;
    while ((node = walker.nextNode())) {
      var text = (node.nodeValue || '').trim();
      if (text.length < 8 || text.length > 400) continue;
      if (!APPROVAL_RE.test(text)) continue;
      if (seenPrompts.has(text)) continue;
      seenPrompts.add(text);
      // Hide the raw prompt node so the user only sees the native sheet.
      var el = node.parentElement;
      if (el) el.style.display = 'none';
      try {
        DshBridge.postApprovalRequest(JSON.stringify({
          id: 'web-' + Date.now() + '-' + (text.length * 31 + text.charCodeAt(0)),
          summary: text.slice(0, 280),
          source: 'webview-dom',
        }));
      } catch (e) { /* bridge unavailable — leave hidden */ }
    }
  }

  /**
   * Called by native code with the Bottom Sheet decision.
   * @param {string} requestId id echoed back from postApprovalRequest
   * @param {boolean} approved true = Approve, false = Deny
   */
  window.__dshResolveApproval = function (requestId, approved) {
    // CLI-gateway backends: gateway.js owns the child's stdin, so hand the
    // answer to its resolver (POSTs yes/no). Checked first to beat the
    // generic composer fallback below.
    if (typeof window.__gatewayResolve === 'function') {
      window.__gatewayResolve(approved);
      return true;
    }
    // Answer the most recent visible confirm control the web UI exposed,
    // falling back to typing y/n into the focused composer.
    var approveBtn = document.querySelector(
      '[data-action="approve"],[data-action="confirm"],button.approve');
    var denyBtn = document.querySelector(
      '[data-action="deny"],[data-action="reject"],button.deny');
    var target = approved ? approveBtn : denyBtn;
    if (target) { target.click(); return true; }
    var composer = document.querySelector(
      '[data-composer] input,[data-composer] textarea,.chat-input-container textarea');
    if (composer) {
      composer.focus();
      document.execCommand('insertText', false, approved ? 'yes' : 'no');
      var form = composer.closest('form');
      if (form) {
        var send = form.querySelector('[type="submit"],button.send');
        if (send) send.click();
        else form.requestSubmit();
      }
      return true;
    }
    return false;
  };

  /* ------------------------------------------------------------------ */
  /* 4. Document height repair                                            */
  /* ------------------------------------------------------------------ */
  /**
   * The agent SPA ships `html,body,#root { height: 100% }`. Inside an
   * Android WebView that percentage chain resolves against a zero-height
   * containing block, so all three collapse to 0px, body.scrollHeight is 0,
   * and the whole interface paints as a black rectangle with no error
   * anywhere: the DOM is fully populated and the onboarding card sits at
   * y=313 with correct geometry, but nothing is visible.
   *
   * `vh` resolves against the viewport rather than the parent, so it
   * survives the broken chain. Measured on an SM_X115 at 601x898dp:
   * html/body/#root all 0px before, 898px after.
   */
  /* ------------------------------------------------------------------ */
  /* Viewport-height repair: `vh` resolves to 0 in this WebView         */
  /* ------------------------------------------------------------------ */

  /**
   * The measured viewport height in px.
   *
   * Prefers visualViewport when present, because it tracks the real visible
   * area when the soft keyboard is up, and innerHeight otherwise.
   */
  function viewportHeight() {
    var vv = window.visualViewport && window.visualViewport.height;
    var h = (typeof vv === 'number' && vv > 0) ? vv : window.innerHeight;
    // Never hand back something that would re-collapse the document.
    return Math.max(1, Math.round(h || 0)) + 'px';
  }

  var VH_REPAIR_ID = 'dsh-vh-repair';

  function installViewportHeightRepair() {
    var doc = document.documentElement;
    doc.style.setProperty('--dsh-vh', viewportHeight());
    syncTouchTarget();

    var sheet = document.getElementById(VH_REPAIR_ID);
    if (!sheet) {
      sheet = document.createElement('style');
      sheet.id = VH_REPAIR_ID;
      /*
       * `!important` is required and deliberate: the agent's own sheet sets
       * these rules with `!important` too, and without matching priority a
       * plain author rule loses. Selectors are attribute-substring matches on
       * the generated class names, so this survives CSS-in-JS class rehashing.
       *
       * Each rule mirrors the agent's own declaration with `vh` replaced by the
       * measured value. Only height-bearing surfaces are touched: dialogs,
       * popover menus, the settings panel and the image/code viewers. Width is
       * NOT broken (`vw` resolves correctly) so no `vw` rule is needed.
       */
      sheet.textContent = [
        /*
         * Touch targets.
         *
         * Measured on-device: 27 of 30 visible buttons were under the 48dp
         * floor, the smallest at 12 CSS px. That is the "can barely see and
         * touch" half of the complaint - a correct-looking dialog is still
         * unusable if the controls cannot be hit.
         *
         * Only *size* is touched. Padding is added rather than height, so the
         * agent's own layout and typography are untouched; min-height would
         * fight its row heights instead of growing the hit area.
         */
        /*
         * Specificity note: the agent sets `min-height:28px` on its own class
         * selectors (the workspace and mode triggers), which outranks a bare
         * `button` rule. `button[class]` is used instead so the override
         * actually wins rather than silently losing to a more specific rule.
         */
        'button,button[class]{min-height:var(--dsh-touch,64px)}',
        '[role="menuitem"],[role="option"],a[role="button"]{min-height:56px}',
        // Icon-only controls need width too, or they stay a 36px sliver.
        'button[aria-label]{min-width:var(--dsh-touch,64px)}',
        // Menus and popovers can overflow when every row grows; keep them
        // scrollable instead of clipping.
        '[class*="_menu"]{overflow-y:auto}',
        // Dialogs / sheets: max-height: calc(100vh - 48px)
        '[class*="_content"]{max-height:calc(var(--dsh-vh) - 48px) !important}',
        // Settings panel: height: min(800px, 100vh - 48px)
        '[class*="_panel"]{height:min(800px,calc(var(--dsh-vh) - 48px)) !important}',
        // Popover menus: max-height variants expressed in vh
        '[class*="_menu"]{max-height:calc(var(--dsh-vh) - 32px) !important}',
        // Code viewer: max-height: min(52vh, 480px)
        '[class*="_viewerCode"]{max-height:min(calc(var(--dsh-vh) * .52),480px) !important}',
        // Image viewer: max-height: calc(100vh - 80px)
        '[class*="_image"]{max-height:calc(var(--dsh-vh) - 80px) !important}',
      ].join('\n');
      (document.head || doc).appendChild(sheet);
    }
  }

  /** Re-measure after a resize, keyboard show/hide, or rotation. */
  function syncViewportHeight() {
    document.documentElement.style.setProperty('--dsh-vh', viewportHeight());
    syncTouchTarget();
  }

  /**
   * 48dp expressed in CSS pixels.
   *
   * The floor is specified in dp, but CSS lengths here are px, and this device
   * runs at devicePixelRatio 1.33 - so a hard-coded 48px would be a 36dp
   * control, still below the floor it claims to enforce. Derived from the
   * live ratio and clamped, because a huge ratio would otherwise produce an
   * unusable button.
   */
  function syncTouchTarget() {
    var dpr = window.devicePixelRatio || 1;
    var px = Math.round(Math.min(Math.max(48 * dpr, 48), 72));
    document.documentElement.style.setProperty('--dsh-touch', px + 'px');
  }

  function repairDocumentHeight() {
    var doc = document.documentElement;
    var body = document.body;
    var root = document.getElementById('root');
    if (!doc || !body) return false;
    var collapsed =
      doc.clientHeight === 0 || body.offsetHeight === 0 ||
      (root && root.offsetHeight === 0);
    if (!collapsed) return false;
    if (doc.style.getPropertyValue('--dsh-height-repaired') === '1') return false;
    doc.style.setProperty('--dsh-height-repaired', '1');
    // A measured pixel height, never `100vh`.
    //
    // An earlier revision repaired the collapse with `min-height:100vh`, which
    // is self-defeating here: `vh` is exactly the unit that is broken (see
    // installViewportHeightRepair), so it re-collapsed the document.
    doc.style.setProperty('min-height', viewportHeight(), 'important');
    doc.style.setProperty('height', viewportHeight(), 'important');
    body.style.setProperty('min-height', viewportHeight(), 'important');
    body.style.setProperty('height', 'auto', 'important');
    if (root) {
      root.style.setProperty('min-height', viewportHeight(), 'important');
      root.style.setProperty('height', 'auto', 'important');
    }
    return true;
  }

  /* ------------------------------------------------------------------ */
  /* Boot + SPA re-application                                           */
  /* ------------------------------------------------------------------ */
  function apply() {
    ensureStyles();
    // One decision, two consumers: the injected chrome and the stylesheet both
    // read this class, so they cannot disagree.
    document.body.classList.toggle('dsh-native-wide', isWide());
    ensureChrome();
    installViewportHeightRepair();
    repairDocumentHeight();
    scanForApprovalPrompts(document.body);
  }

  apply();

  // Rotating or resizing crosses the breakpoint, which swaps the whole
  // presentation. Without this listener a tablet rotated to portrait keeps
  // its wide layout and vice versa until the next page load.
  // Resize, not matchMedia: the media height is pinned at ~0 in this WebView,
  // so its change event never fires on rotation.
  window.addEventListener('resize', function () {
    syncViewportHeight();
    apply();
  });

  // The soft keyboard changes the visible viewport without a window resize on
  // every OEM path, so track visualViewport directly too.
  if (window.visualViewport) {
    window.visualViewport.addEventListener('resize', syncViewportHeight);
    window.visualViewport.addEventListener('scroll', syncViewportHeight);
  }

  new MutationObserver(function (mutations) {
    var needsScan = false;
    for (var i = 0; i < mutations.length; i++) {
      if (mutations[i].addedNodes.length) { needsScan = true; break; }
    }
    if (needsScan) {
      ensureChrome();
      repairDocumentHeight();
      scanForApprovalPrompts(document.body);
    }
  }).observe(document.documentElement, { childList: true, subtree: true });

  // The SPA re-renders on route changes, so a later pass can re-collapse the
  // document. Cheap, and guarded above to be a no-op once healthy.
  setInterval(repairDocumentHeight, 1000);
  window.__dshRepairHeight = repairDocumentHeight;
})();
