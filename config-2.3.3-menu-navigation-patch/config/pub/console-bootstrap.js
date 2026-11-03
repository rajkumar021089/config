(function (window, document) {
  'use strict';
  var build = '233-nav2';
  function report(message) {
    var notice = document.getElementById('notice');
    if (notice) notice.textContent = message;
  }
  function load() {
    var existing = window.ConfigConsole233;
    if (existing && existing.build === build && existing.root === document.getElementById('notice')) {
      existing.start();
      return;
    }
    var script = document.createElement('script');
    script.src = '/config/console.js?v=' + build;
    script.onload = function () {
      var consoleApp = window.ConfigConsole233;
      if (!consoleApp || consoleApp.build !== build || consoleApp.root !== document.getElementById('notice')) {
        report('Configuration console did not initialize. Check the browser Console for a JavaScript error and verify console.js?v=' + build + ' in Network.');
        return;
      }
      consoleApp.start();
    };
    script.onerror = function () {
      report('Could not load /config/console.js?v=' + build + '. Check its Network response, login, and the installed console.js file.');
    };
    report('Loading configuration console (' + build + ')…');
    document.head.appendChild(script);
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', load, {once: true});
  else load();
})(window, document);
