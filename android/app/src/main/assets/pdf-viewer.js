// Android WebView can't display PDFs; hand the site's <object type="application/pdf"> and .pdf links to the app's native reader.
(() => {
  if (window.__chPdf) return;
  window.__chPdf = true;

  function open(url, title) {
    if (!url || !window.CourseHunterApp) return;
    CourseHunterApp.openPdf(new URL(url, location.href).href, title || '');
  }

  var CARD_CSS =
    'display:flex;flex-direction:column;align-items:center;justify-content:center;gap:6px;width:100%;min-height:200px;' +
    'margin:0;padding:24px;border:0;border-radius:12px;background:#f3eef7;color:#1c1c1e;font:15px/1.4 -apple-system,Roboto,sans-serif;text-align:center';

  function card(obj) {
    var b = document.createElement('button');
    b.type = 'button';
    b.style.cssText = CARD_CSS;
    var icon = document.createElement('span');
    icon.textContent = '📖';
    icon.style.fontSize = '40px';
    var label = document.createElement('span');
    label.textContent = 'Read book';
    label.style.cssText = 'font-weight:600;font-size:17px;color:#8200b0';
    var title = document.createElement('span');
    title.textContent = obj.getAttribute('aria-label') || '';
    title.style.opacity = '.6';
    b.append(icon, label, title);
    b.onclick = () => {
      open(obj.__chUrl, obj.getAttribute('aria-label'));
    };
    return b;
  }

  function upgradeObjects() {
    document
      .querySelectorAll('object[type="application/pdf"]')
      .forEach((obj) => {
        if (obj.__chCard) return;
        // book-read.js hides the reader when the browser lacks a built-in PDF plugin; we provide one.
        var wrap = obj.closest('.book-wrap-pdf');
        if (wrap) {
          // classList.remove rewrites `class` even when the token is absent, which would re-trigger this observer forever.
          var unhide = () => {
            if (wrap.classList.contains('hidden'))
              wrap.classList.remove('hidden');
          };
          unhide();
          new MutationObserver(unhide).observe(wrap, {
            attributes: true,
            attributeFilter: ['class'],
          });
          wrap.style.height = 'auto';
          wrap.style.aspectRatio = 'auto';
        }
        // Keep the <object> (hidden) so the site's own scripts can still swap its `data`.
        obj.__chCard = card(obj);
        obj.style.display = 'none';
        obj.after(obj.__chCard);
        // Even hidden, the <object> downloads the whole PDF; keep its URL and drop `data` to cancel that.
        var take = () => {
          var url = obj.getAttribute('data');
          if (!url) return;
          obj.removeAttribute('data');
          obj.__chUrl = url;
        };
        take();
        new MutationObserver(take).observe(obj, {
          attributes: true,
          attributeFilter: ['data'],
        });
      });
  }

  document.addEventListener(
    'click',
    (e) => {
      // The site's "On full screen" button relies on the missing PDF plugin; open the current book natively.
      var show = e.target.closest && e.target.closest('[data-book="show"]');
      var obj =
        show && document.querySelector('object[type="application/pdf"]');
      if (obj && obj.__chUrl) {
        e.preventDefault();
        e.stopPropagation();
        return open(obj.__chUrl, obj.getAttribute('aria-label'));
      }
      var a = e.target.closest && e.target.closest('a[href]');
      if (!a || !/\.pdf(\?|#|$)/i.test(a.href)) return;
      e.preventDefault();
      e.stopPropagation();
      open(a.href, a.textContent.trim());
    },
    true,
  );

  upgradeObjects();
  new MutationObserver(upgradeObjects).observe(document.documentElement, {
    childList: true,
    subtree: true,
  });
})();
