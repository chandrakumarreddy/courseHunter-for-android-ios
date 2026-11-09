---
title: Network errors after page load must not show an error to the user
author: Chandrakumarreddy
date: 2026-11-09
standing: true
id: l-ac0eecfb
scope: repo
status: active
src: init-sessions
touches:
  - src/App.tsx
---

## Watch out
- Android's WebView reports failed sub-requests like video segments through the same error event as page load failures, so playing videos triggered a false error.
- In the code: In src/App.tsx, show the error toast only before the page has loaded; later errors only console.warn (dev builds only).

_From past Claude sessions here, Oct 20._
