---
title: Stale Kotlin build cache breaks builds after mixing build locations
author: Chandrakumarreddy
date: 2026-11-09
standing: true
id: l-20bd4f52
scope: repo
status: active
src: init-sessions
---

## Watch out
- Compiling debug from inside android/ then release from the project root left a stale incremental cache that failed the build despite valid source.
- In the code: Run: rm -rf android/app/build/kotlin android/app/build/tmp && ./android/gradlew -q -p android --stop, then bun run build:android again.

_From past Claude sessions here, Nov 9._
