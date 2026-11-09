---
title: Users got logged out on reopen because cookies were not saved to disk
author: Chandrakumarreddy
date: 2026-11-09
standing: true
id: l-b134776f
scope: repo
status: active
src: init-sessions
---

## Watch out
- Android's WebView only persists cookies periodically, so closing the app soon after login lost the session.
- In the code: MainActivity.onPause() calls CookieManager.getInstance().flush(). Keep it when touching lifecycle code.

_From past Claude sessions here, Oct 20._
