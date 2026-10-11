/**
 * dsh-mobile-shell.js — injected into the agent WebView on every page load.
 *
 * Transforms the desktop-focused DeepSeek Harness three-column layout
 * (sidebar / chat / file manager) and the OpenClaw dashboard into a
 * touch-driven mobile shell:
 *
 *  - Sidebars collapse into swipeable sliding drawers (hamburger toggle).
 *  - The file manager moves into a bottom tab next to Chat.
 *  - The text composer is pinned to the bottom, above the keyboard.
 *  - Tool-execution prompts ("Do you want to run…?", "Allow file
 *    modification?") are intercepted from the DOM and forwarded to the
 *    native Android Bottom Sheet via the `DshBridge` JS interface instead
 *    of rendering as terminal text.
 *  - Raw <pre>/xterm terminal nodes are hidden — the user never sees a CLI.
 *
 * The WebView enables this file as a post-load script; it is idempotent and
 * re-runs on SPA route changes via MutationObserver.
 */
(function () {
  'use strict';
  if (window.__dshMobileShellInstalled) return;
  window.__dshMobileShellInstalled = true;

  var SHELL_ID = 'dsh-mobile-shell';
  var DRAWER_ID = 'dsh-mobile-drawer';
  var TABBAR_ID = 'dsh-mobile-tabbar';
  var TOGGLE_ID = 'dsh-mobile-nav-toggle';

  /* ------------------------------------------------------------------ */
  /* 1. Stylesheet: mobile shell transformation                          */
  /* ------------------------------------------------------------------ */
  function ensureStyles() {
    if (document.getElementById(SHELL_ID)) return;
    var css = [
      ':root{--dsh-mobile-bar:#0B0F14;--dsh-mobile-accent:#4DA3FF;}',
      // Hide raw terminal surfaces entirely.
      '.xterm,.xterm-screen,.terminal-container,pre.terminal-output,[data-terminal-root]{display:none!important;}',
      // Collapse left/right sidebars into off-canvas drawers.
      '.dsh-sidebar,.sidebar,[data-panel="sidebar"],aside:first-of-type{',
      '  position:fixed!important;top:0;bottom:0;left:0;width:min(84vw,340px)!important;',
      '  transform:translateX(-105%);transition:transform .24s ease;z-index:60;',
      '  box-shadow:0 0 32px rgba(0,0,0,.5);overflow-y:auto;}',
      'body.dsh-drawer-open .dsh-sidebar,body.dsh-drawer-open .sidebar,',
      'body.dsh-drawer-open [data-panel="sidebar"]{transform:none!important;}',
      // Main column becomes full-width; file manager becomes a tab pane.
      '.dsh-main,[data-panel="main"],main{margin-left:0!important;width:100%!important;}',
      '[data-panel="files"],.dsh-file-manager,.file-explorer{display:none!important;}',
      'body.dsh-tab-files [data-panel="files"],body.dsh-tab-files .dsh-file-manager,',
      'body.dsh-tab-files .file-explorer{display:block!important;position:static!important;',
      '  width:100%!important;transform:none!important;}',
      'body.dsh-tab-files [data-panel="chat"],body.dsh-tab-files .dsh-chat{display:none!important;}',
      // Pin the composer above the keyboard.
      '.dsh-composer,[data-composer],.chat-input-container{',
      '  position:sticky!important;bottom:0;z-index:40;padding-bottom:env(safe-area-inset-bottom);}',
      // Hamburger + bottom tab bar injected by this script.
      '#' + TOGGLE_ID + '{position:fixed;top:10px;left:10px;z-index:70;width:44px;height:44px;',
      '  border-radius:12px;border:1px solid rgba(255,255,255,.14);background:var(--dsh-mobile-bar);',
      '  color:#fff;font-size:20px;}',
      '#' + TABBAR_ID + '{position:fixed;left:0;right:0;bottom:0;z-index:55;display:flex;',
      '  background:var(--dsh-mobile-bar);border-top:1px solid rgba(255,255,255,.1);',
      '  padding-bottom:env(safe-area-inset-bottom);}',
      '#' + TABBAR_ID + ' button{flex:1;padding:12px 0;background:none;border:none;color:#9AA4B2;font-size:13px;}',
      '#' + TABBAR_ID + ' button.active{color:var(--dsh-mobile-accent);font-weight:600;}',
      '#' + DRAWER_ID + '-scrim{position:fixed;inset:0;background:rgba(0,0,0,.45);z-index:59;display:none;}',
      'body.dsh-drawer-open #' + DRAWER_ID + '-scrim{display:block;}',
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
    if (!document.getElementById(TOGGLE_ID)) {
      var toggle = document.createElement('button');
      toggle.id = TOGGLE_ID;
      toggle.textContent = '☰';
      toggle.setAttribute('aria-label', 'Open navigation');
      toggle.addEventListener('click', function () {
        document.body.classList.toggle('dsh-drawer-open');
      });
      document.body.appendChild(toggle);

      var scrim = document.createElement('div');
      scrim.id = DRAWER_ID + '-scrim';
      scrim.addEventListener('click', function () {
        document.body.classList.remove('dsh-drawer-open');
      });
      document.body.appendChild(scrim);
    }
    if (!document.getElementById(TABBAR_ID)) {
      var bar = document.createElement('div');
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
  /* Boot + SPA re-application                                           */
  /* ------------------------------------------------------------------ */
  /* ------------------------------------------------------------------ */
  /* 4. Document height repair                                            */
  /* ------------------------------------------------------------------ */
  /**
   * The agent SPA ships `html,body,#root { height: 100% }`. Inside an
   * Android WebView that percentage chain resolves against a zero-height
   * initial containing block, so all three collapse to 0px, `body`
   * scrollHeight becomes 0, and the whole interface paints as a black
   * rectangle with no error anywhere: the DOM is fully populated (the
   * onboarding card sits at y=313 with correct geometry) but nothing is
   * visible.
   *
   * `vh` units resolve against the viewport itself rather than against the
   * parent, so they survive the broken percentage chain. Measured on an
   * SM_X115 at 601x898dp: html/body/#root all 0px before, 898px after.
   */
  function repairDocumentHeight() {
    var doc = document.documentElement;
    var root = document.getElementById('root');
    var needsRepair =
      doc.clientHeight === 0 ||
      document.body.offsetHeight === 0 ||
      (root && root.offsetHeight === 0);
    if (!needsRepair) return false;
    if (getComputedStyle(doc).getPropertyValue('--dsh-height-repaired') === '1') {
      return false;
    }
    doc.style.setProperty('--dsh-height-repaired', '1');
    doc.style.setProperty('min-height', '100vh', 'important');
    doc.style.setProperty('height', '100%', 'important');
    if (document.body) {
      document.body.style.setProperty('min-height', '100vh', 'important');
      document.body.style.setProperty('height', 'auto', 'important');
    }
    if (root) {
      root.style.setProperty('min-height', '100vh', 'important');
      root.style.setProperty('height', 'auto', 'important');
    }
    return true;
  }

  function apply() {
    ensureStyles();
    ensureChrome();
    repairDocumentHeight();
    scanForApprovalPrompts(document.body);
  }

  apply();
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
  // document. Cheap enough to re-check on a timer, and the guard above means
  // this is a no-op once the page is healthy.
  setInterval(repairDocumentHeight, 1000);

  // Expose the diagnosis for on-device debugging over the devtools bridge.
  window.__dshRepairHeight = repairDocumentHeight;
})();
