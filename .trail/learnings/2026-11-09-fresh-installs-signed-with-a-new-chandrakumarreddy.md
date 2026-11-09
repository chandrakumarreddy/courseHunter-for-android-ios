---
title: Fresh installs signed with a new key wipe login; use install -r
author: Chandrakumarreddy
date: 2026-11-09
standing: true
id: l-b1e3f18e
scope: repo
status: active
src: init-sessions
touches:
  - coursehunter.apk
  - android/release.jks
---

## Watch out
- Switching from the debug key to the release key forced an uninstall, which deleted the cookies and logged the user out. Android refuses to install over a copy signed with a different key.
- In the code: Update with `adb install -r coursehunter.apk`; never uninstall unless the signing key changes. Back up android/release.jks and android/keystore.properties. This phone prints DELETE_FAILED_INTERNAL_ERROR even when uninstall succeeds, so don't chain with &&.

_From past Claude sessions here, Oct 20._
