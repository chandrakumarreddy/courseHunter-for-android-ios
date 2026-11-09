---
title: APK was 52 MB because of native libraries for three CPU types
author: Chandrakumarreddy
date: 2026-11-09
standing: true
id: l-c7d9b816
scope: repo
status: active
src: init-sessions
touches:
  - coursehunter.apk
---

## Watch out
- The webview package bundled native libs for several architectures; switching to a smaller webview library and trimming dropped it to about 5 to 6 MB.
- In the code: Check APK size after changing xelement or webview dependencies. Current release is about 5 MB via bun run build:android, output ./coursehunter.apk.

_From past Claude sessions here, Oct 20._
