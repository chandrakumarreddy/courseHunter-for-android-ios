---
title: "Strip debug code only from release APK builds, keep it in source"
author: Chandrakumarreddy
date: 2026-11-09
standing: true
id: l-5946e1ea
scope: repo
status: active
src: init-sessions
touches:
  - lynx.config.ts
---

## Decided
- The user wanted no debugging in the shipped app but still wanted dev reload and devtools during development. Removing it from the code was reverted in favor of build-time gating.
- In the code: Gating is in lynx.config.ts (production-only: devtools imports aliased to empty modules, performance.removeConsole) and proguard-rules.pro (strips Log.e/wtf/printStackTrace). Dev reload and dev-server loading are behind BuildConfig.DEBUG. Don't delete them from source.

_From past Claude sessions here, Oct 23._
