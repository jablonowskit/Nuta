# SABR/UMP Research Findings (2026-08-23)

Purpose: pure research to inform a "do we implement SABR" decision for Nuta, after
confirming via CDP that real Chrome playback is 100% SABR/UMP end-to-end, and that our
current `VISIONOS` client is a temporary reprieve (4th client in this situation).

Scope follows `sabr-research-plan.md`'s three numbered sections exactly.

---

## 1. Existing documentation / implementations of UMP/SABR

### yt-dlp PR #13515 (`coletdjnz`, branch `fd/sabr`)
- Adds a **native SABR downloader**, not a player — it's a `FileDownloader` subclass
  (`SabrFD` in `yt_dlp/downloader/sabr/_fd.py`), plus `_state.py` (protobuf-based
  persistence of segment/progress state) and `_io.py` (concurrent buffered read/write).
  This is architecturally a *download-to-disk* client, not a streaming player — it
  fetches and reassembles segments into a file yt-dlp then hands to ffmpeg/muxing, which
  is a different problem shape than what Nuta needs (feed a live/seekable stream into
  ExoPlayer/mpv).
- Size: **227 commits**, has been in review 14+ months, still open/unmerged as of this
  research. Depends on an external `protobug` library for protobuf parsing (degrades
  gracefully without it, but SABR literally *is* protobuf-over-UMP so it's a hard
  functional dependency in practice).
- Requires PO Token; includes "automatic PO Token re-fetching on expiration" and GVS
  fallback-server handling — i.e. real production hardening was needed, not a toy path.
- Reviewer discussion flags real correctness gaps even after 14 months: livestream
  stalls with `ATTESTATION_PENDING`, open questions about state-file sizing and
  segment-pairing logic, and the author explicitly deferred "cold-start" PO Token
  handling as out of scope / backlog item, not solved.
- Conclusion: **this PR is not a reusable, extractable "SABR client" component** — it's
  deeply wired into yt-dlp's downloader framework and still incomplete/unmerged despite
  a full team-scale effort. Not directly portable to Kotlin/KMP; useful only as a
  reference for protocol behavior, not as code to port.

### UMP wire format — best available documentation
- `gsuberland/UMP_Format` (github.com/gsuberland/UMP_Format) is the clearest
  from-scratch spec available: a length-prefixed, typed "parts" framing over HTTP
  response bodies.
  - Every part is `[type varint][length varint][payload]`. Varints use a
    RFC8794-like variable-length scheme (top bits of first byte signal 1–5 byte width).
  - Key part types confirmed: **20 = MEDIA_HEADER** (video id, itag, `lmt` timestamp,
    content length), **21 = MEDIA** (actual audio/video bytes, null-byte prefixed),
    **22 = MEDIA_END** (terminator).
  - Parts can be **split across multiple HTTP response chunks** — a part's declared
    length can exceed what's in the current chunk, continuing in the next one, each of
    which itself starts with its own type-20 header. This means a UMP parser needs a
    genuine incremental/stateful reader, not a single-shot buffer parse.
  - Part types 31–64 exist for livestream-specific behavior (bandwidth sampling,
    playback policy, sabr-context updates) and are described in the doc as **largely
    undocumented** even in this best-available reference.
- This is a real, non-trivial protocol, but it is genuinely documented well enough
  that writing a parser from scratch is *feasible* — this is the good news of the
  research.

### Standalone/smaller libraries — yes, one exists and matches what the plan hoped for
- **`LuanRT/googlevideo`** (npm/JSR: `@luanrt/googlevideo`) is exactly the
  "separated-out, smaller than yt-dlp" UMP/SABR library the plan asked about.
  - TypeScript, ~127 commits, organized into `src` / `protos` / `tests` / `examples`.
  - Contains a UMP reader/writer, protobuf definitions for media headers and parts, a
    `SabrUmpProcessor` that turns UMP parts into audio/video segments, and a
    `SabrStreamingAdapter` for hooking into a media player (their demo targets Shaka
    Player, a JS/web DASH-like player — not directly relevant to ExoPlayer/mpv, but the
    *shape* of the adapter is instructive).
  - Meant to be usable independently of the full `youtube.js`/`youtubei.js` project,
    though it's part of the same author's ecosystem and best understood alongside it.
  - Sibling projects from the same author: `LuanRT/kira` (a demo YouTube web client
    playing via SABR/googlevideo) and `LuanRT/ump-inspector` (a browser extension that
    decodes UMP traffic for debugging) — useful as *reference implementations to read*,
    not as code to embed (they're JS/TS, web-targeted).
  - Does not appear to include PO Token generation itself — that is a separate concern
    (BotGuard/DroidGuard), consistent with what we already knew.
  - **This is the best lead for understanding the protocol's shape without reading all
    of yt-dlp**, but it is still a from-scratch reimplementation task for a KMP/Kotlin
    target — there is no JVM/Kotlin port, and no C library usable from mpv/desktop.

### NewPipeExtractor
- Confirmed: NewPipe/NewPipeExtractor is **actively fighting the same client-rotation
  problem we are**, not implementing SABR. Recent issues (#12126 "Only 360p
  video/audio-video streams — SABR enforcement", #13320 similarly titled) show they
  keep patching around SABR by finding/rotating to non-SABR clients, exactly like our
  `VISIONOS` approach — this is explicitly a stopgap for them too, not a strategy they
  consider durable.
- There is a coordinating/tracking issue mentioned in search results (issue #12248-style
  numbering) for community SABR implementation efforts spanning yt-dlp, LibreTube, and
  others — i.e. **no mature player-side (not downloader-side) open-source SABR client
  exists yet that anyone has finished**, across the entire ecosystem, as of this
  research. This is an important data point: multiple better-resourced OSS projects
  have had this problem for well over a year and none has shipped a complete solution.

### PO Token mechanics on the SABR path (confirmed, extends earlier findings)
- With `sabr=1`, the PO Token travels inside the **request body protobuf**, not a URL
  query param — harder to intercept/reuse than the old `pot=` query param.
  - The server communicates token requirement via an `sps` status field in media
    responses: status 1 = token valid/not needed, status 2 = token required but a
    grace window of 1–2MB is allowed before playback stops, status 3 = token mandatory,
    zero further bytes served without it.
  - PO Tokens for the web player path are **tied to a specific video ID** — one token
    generation per video, not a session-wide token. This raises real per-track latency
    and refresh-logic concerns for a jukebox-style app that switches tracks frequently.
  - Generating them requires running Google's BotGuard JS challenge inside a real,
    reasonably modern WebView/JS engine (`DroidGuard` on Android specifically, not
    generic BotGuard) — consistent with the desktop WebView2/JCEF migration work already
    tracked elsewhere in this repo, but this is a *new, separate* JS execution surface
    (BotGuard/DroidGuard challenge), not the same code path as the existing
    Spotify-token-via-WebView work.

---

## 2. Real scope of work to implement in Nuta (KMP: Android + desktop)

Point by point, what would actually need to be built:

1. **UMP parser/encoder** (shared/commonMain candidate)
   - A streaming, incremental binary reader implementing the `gsuberland/UMP_Format`
     spec: varint decoding, part framing, cross-chunk part continuation, protobuf
     decoding of MEDIA_HEADER/MEDIA/MEDIA_END and the ~30 other part types actually
     needed for VOD audio (livestream-only part types can likely be skipped).
   - This piece alone is genuinely buildable by one person — the format is documented
     well enough, and it's "just" careful binary parsing. Realistic estimate: **several
     days to ~1-2 weeks** of focused work for a correct, tested VOD-only parser (not
     counting livestream edge cases, which should be explicitly out of scope).

2. **SABR client/session layer**
   - Unlike the old model (one signed GET URL → `DefaultHttpDataSource` handles Range
     requests transparently), SABR needs: an explicit session with the `/videoplayback`
     SABR endpoint, POST requests carrying protobuf bodies (format selection, requested
     byte ranges/init segments, live sequence numbers if applicable), and manual
     mapping of returned UMP MEDIA parts back into a byte stream ExoPlayer/mpv can
     consume as if it were a normal progressive file.
   - This **cannot be a drop-in `DefaultHttpDataSource` swap** — the plan's suspicion is
     correct. On Android this means a custom `DataSource` (implementing `open()`/
     `read()`/`close()`) that internally runs the SABR request/response state machine
     and buffers demuxed media bytes for ExoPlayer to pull, likely combined with a
     custom `MediaSource`/`MediaPeriod` if init-segment/format-selection semantics don't
     map cleanly onto a single progressive byte stream (audio-only itag selection may
     simplify this since Nuta doesn't need adaptive video).
   - This is the largest unknown-sized piece of work. Estimate: **2-4 weeks** for an
     experienced Android/Kotlin developer working evenings/weekends, assuming the UMP
     parser above already works and assuming audio-only simplifies away a lot of the
     ABR/multi-track complexity that makes yt-dlp's PR so large. Real risk this balloons
     if seeking/scrubbing needs mid-stream re-requests with new byte ranges (very likely,
     given SABR is explicitly range/segment-oriented).

3. **PO Token generation in production**
   - Must run a BotGuard/DroidGuard JS challenge and get back a valid, per-video token
     before the first SABR request, then handle the "sps status 2" grace window and
     token refresh if a track plays long enough or the token expires.
   - On Android, this likely means driving the same challenge via a hidden WebView (the
     existing Android-first plan already assumes a system WebView is available, so the
     execution environment exists) — but this is **new integration work**, not reuse of
     anything already built for Spotify. The per-video token requirement is the biggest
     new wrinkle versus everything done for Spotify's OAuth-in-webview work: it must run
     fast enough, per track, without visibly stalling playback start.
   - Estimate: **1-2 weeks**, contingent on how easy it is to drive BotGuard/DroidGuard
     from a Kotlin-controlled WebView without a full browser context (real risk of
     needing bot-detection-evasion levels of fidelity, similar in kind to the JCEF/
     WebView2 Spotify problem already documented, but for a different, less-studied
     challenge).

4. **Desktop (mpv) playback**
   - mpv has **zero built-in UMP/SABR awareness** and never will (it's a general media
     player, not a YouTube client). The only workable approach is exactly what the plan
     guessed: a **local HTTP proxy** in `desktopMain` that:
     - runs the same SABR client/UMP parser logic as Android (shared KMP code, which is
       the one clean win of doing this in commonMain),
     - re-assembles the SABR/UMP stream into a normal, seekable, Range-request-capable
       HTTP resource,
     - and serves that to mpv exactly like today's plain `videoplayback` URLs are served.
   - This is feasible **without writing a custom audio decoder** — the proxy only needs
     to speak SABR upstream and plain HTTP+Range downstream, letting mpv's own demuxer/
     decoder handle the actual audio (m4a/opus) as it does today. This is good news:
     the desktop side is "just" plumbing once the shared SABR/UMP client exists, not a
     second full reimplementation.
   - Estimate: **3-5 days** on top of the shared client, mostly for correct Range-request
     translation and buffering/seek behavior in the local proxy server.

### Rough total
**5-9 weeks of solo, spare-time engineering**, assuming no major surprises in reverse
engineering the exact SABR request protobuf shapes (which are not fully documented
publicly the way MEDIA_HEADER/MEDIA/MEDIA_END are) and assuming BotGuard/DroidGuard
token generation works reliably from a controlled WebView. Either of those assumptions
failing would extend this significantly — most likely by weeks, not days, since both
are exactly the kind of "looks close, then the last 20% is adversarial" problem this
project has already lived through once with Spotify's reCAPTCHA/OAuth wall.

---

## 3. Time/risk estimate and the alternative

### Honest scope assessment
- This is **not a weekend project**. Even with the best-case shared-KMP-code win on the
  desktop proxy, this is realistically **6-10 weeks of solo spare-time work**, and that
  estimate already assumes the UMP wire format documentation is accurate and the
  request-side protobuf shapes (less documented than the response side) aren't a
  second research rabbit hole of their own.
- It is achievable for one person — nothing here requires a team, unlike yt-dlp's
  227-commit effort, because Nuta only needs audio-only VOD playback, not video, not
  livestreams, not adaptive bitrate switching. That materially shrinks the problem
  versus what yt-dlp is solving generally.

### Risk: is this a new arms race, not a one-time fix?
- Yes, almost certainly, and this is the most important finding of the research.
  SABR/UMP is YouTube's *current* delivery mechanism, but:
  - PO Token/BotGuard is explicitly an anti-automation escalation layer that Google
    actively maintains and updates adversarially (same category of problem as the
    Spotify reCAPTCHA/JCEF wall already fought and lost once in this project).
  - The per-video token requirement, the `sps` grace-window status codes, and multiple
    still-open correctness gaps in yt-dlp's 14-month-old, team-built PR all signal an
    actively-defended, moving target — not a stable protocol that, once implemented,
    stays working.
  - Historically this project has already chased four client profiles
    (WEB → TVHTML5 → ANDROID_VR → VISIONOS) as each got SABR-forced in turn. Implementing
    SABR properly does not end that dynamic — it just moves the cat-and-mouse game one
    level down, from "which client profile still gets plain URLs" to "does our BotGuard/
    DroidGuard token minting still pass attestation this month." There is no reason to
    expect that fight to be less maintenance-hungry than the current one.

### Alternative: move off YouTube as an audio source
- Noted per the plan only as a reference point, not developed into a full plan:
  SoundCloud (has a real, documented public-ish API and simpler stream URLs last this
  was generally true) or a self-hosted/self-controlled backend (e.g. a small proxy the
  user runs that does the yt-dlp-style extraction server-side, isolating the arms race
  to one maintained service instead of the client app) are both "less work, different
  problem" options. Both trade YouTube's catalog breadth for lower maintenance risk.

### Recommendation

**Defer SABR implementation.** Concretely:
- Do **not** start building the SABR/UMP client now. The current `VISIONOS` non-SABR
  path should be milked for as long as it lasts, with monitoring/alerting (or just
  periodic manual checking) for when YouTube forces SABR onto it too — consistent with
  the pattern of the last three client migrations.
- When `VISIONOS` does fall, the *first* thing to re-check is whether a fifth
  non-SABR client profile has emerged (this has happened four times already and is the
  cheapest possible mitigation — hours, not weeks).
- Only if no such client exists, and the project's direction still commits to YouTube
  as the audio source, should the ~6-10 week SABR implementation described in section 2
  be seriously scheduled — and even then, budget for it being an ongoing maintenance
  burden (BotGuard/token upkeep), not a one-time fix, the same way the Spotify
  integration already is.
- In parallel, and at much lower cost, it's worth **prototyping SoundCloud or another
  non-YouTube source as a fallback path** — not a full migration, but enough to know
  the option is real if/when SABR enforcement on all remaining clients happens at once
  and there's no time to spend 2+ months on a SABR client under pressure.

The core judgment: SABR is a **feasible-but-substantial, one-person-doable but not
one-person-cheap** piece of engineering that solves today's problem while very likely
recreating tomorrow's (BotGuard arms race) — spending the same weeks now on a
non-YouTube fallback path buys more durable insurance for less certain-to-be-wasted
effort.

---

## Sources
- [yt-dlp PR #13515 — SABR protocol downloader](https://github.com/yt-dlp/yt-dlp/pull/13515)
- [gsuberland/UMP_Format — reverse-engineered UMP spec](https://github.com/gsuberland/UMP_Format/blob/main/UMP_Format.md)
- [LuanRT/googlevideo — UMP/SABR utility library](https://github.com/LuanRT/googlevideo)
- [LuanRT/kira — SABR playback demo via YouTube.js & googlevideo](https://github.com/LuanRT/kira)
- [LuanRT/ump-inspector — browser extension for decoding UMP traffic](https://github.com/LuanRT/ump-inspector)
- [LuanRT/yt-sabr-shaka-demo — SABR/UMP + Shaka Player demo](https://github.com/LuanRT/yt-sabr-shaka-demo)
- [yt-dlp/yt-dlp-ytse #17 — SABR Downloader discussion](https://github.com/coletdjnz/yt-dlp-ytse/issues/17)
- [yt-dlp/yt-dlp #12482 — "web" only has SABR formats](https://github.com/yt-dlp/yt-dlp/issues/12482)
- [TeamNewPipe/NewPipe #12126 — SABR enforcement, only 360p](https://github.com/TeamNewPipe/NewPipe/issues/12126)
- [TeamNewPipe/NewPipe #13320 — SABR enforcement, no separate audio](https://github.com/TeamNewPipe/NewPipe/issues/13320)
- [yt-dlp wiki — PO Token Guide](https://github.com/yt-dlp/yt-dlp/wiki/PO-Token-Guide/e6c51bd16bbfd532d05ca551eb408a95093ee9d3)
- [LuanRT/BgUtils — PoToken/BotGuard attestation utility](https://github.com/LuanRT/BgUtils)
- [YouTube's New Heart: The "SABR Protocol" Practical Guide (note.com)](https://note.com/_gomadango/n/nbdaa165c11b0?hl=en)
