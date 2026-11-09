---
title: "Replaced pdf.js with Android's native PDF renderer after freezes and memory blowups"
author: Chandrakumarreddy
date: 2026-11-09
standing: true
id: l-9cf56e77
scope: repo
status: active
src: init-sessions
---

## Decided
- The in-page pdf.js viewer rendered every page at once, froze the app and even the whole phone, and kept hitting memory limits. The team moved the reader to a native screen with only nearby pages held in memory.
- In the code: Reader lives in PdfReaderActivity.kt (PDFium engine for text/search/links). pdf-viewer.js is now only a hand-off script. Do not reintroduce pdf.js or in-page canvas rendering.

_From past Claude sessions here, Oct 23._
