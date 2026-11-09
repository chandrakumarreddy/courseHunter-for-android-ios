---
title: Shrinking the Lynx keep rules crashes the app at launch
author: Chandrakumarreddy
date: 2026-11-09
standing: true
id: l-03c60b11
scope: repo
status: active
src: init-sessions
---

## Tried and ruled out
- Lynx native code looks up Java methods by name, and R8 stripped them when the keep rule was narrowed. It saved 0.3 MB but the app crashed at start.
- In the code: Keep the broad Lynx rule in proguard-rules.pro (e.g. LynxLog.log is called from native). Don't narrow it for size.

_From past Claude sessions here, Oct 20._
