# Change: YouTube audio source blocked by SABR rollout

- Status: **client-rotation mitigation in flight (`VISIONOS` profile,
  commit `cdeee89`), outcome not yet confirmed on-device** — not a durable fix
- Date opened: 2026-08-22
- Affects: `youtube-audio-source` (Android side; desktop resolver
  `NutaYouTubeMediaService` not yet re-tested against this but almost
  certainly hits the same wall, since it's the same YouTube backend)

## Why

Playback on Android stopped working reliably around 2026-08-17-22. A long
debugging session (see git log `3a1091e`..`6bdd9cd` on `main`) chased it
through several distinct, real bugs before finding the actual current
blocker. Recording all of it here so nobody re-derives the same path.

## Timeline of what was actually wrong, in order found

1. **Auto-skip-to-next-track feature** (`3a1091e`) introduced a real
   flicker/loop bug: `retryingAfterError` was reset to `false` the instant
   `resolveForPlayback()` succeeded, before playback had actually stabilized —
   so a track failing a few seconds in always looked like a "first" failure
   and never reached the skip/stop threshold. User judged the whole feature
   area unstable and hard-reset `main` back to `6ad0b93` (pre-feature).
   **This reset also silently re-introduced two earlier, already-fixed bugs**
   (see 2 and 3 below) that predated the auto-skip feature entirely.
2. **Duplicate connection to the same signed URL**: a `preloadStreamStart()` /
   `CacheWriter` mechanism opened its own HTTP connection to warm the cache
   for the next 2 queued tracks, ahead of ExoPlayer's own request to the same
   URL — Google's CDN rejects the second connection. Root-caused and removed
   once already this session (`adc8af3`), came back with the hard reset,
   **re-removed** in `ac36540`.
2. **Infinite retry loop** (independent of the auto-skip feature): the same
   `retryingAfterError`-reset-too-early bug from item 1 above was *already*
   present in the pre-3a1091e code path too (it's the plain 1-retry guard,
   not the skip-to-next feature) — just never manifested until 403s became
   persistent enough to trigger it constantly. Fixed via a `stabilityJob`
   gate (delay the reset until `STABLE_PLAYBACK_MS` of confirmed
   `player.isPlaying`) in `52faed0`.
3. **`ANDROID_VR` client blocked by YouTube (2026-08-17)**: confirmed via
   research into yt-dlp issues #17348/#16150 — YouTube started requiring a PO
   (Proof-of-Origin) Token for `ANDROID_VR` audio-only formats; yt-dlp dropped
   it from defaults in release `2026.08.19`. Diagnostic logging (`3e51270`)
   ruled out the "n" throttling parameter as the cause first (URL had no `n=`,
   6h left on `expire=`) before this was found. Switched to plain `ANDROID`
   (`0114931`), which itself needed exact-current field values from yt-dlp's
   `INNERTUBE_CLIENTS['android']` — a guessed `clientVersion` caused HTTP 400
   until corrected (`6257a5b`).
4. **The actual current blocker — SABR** (`5a9bb56` onward): even with a
   correct `ANDROID` client, adaptiveFormats entries come back with **neither
   `url` nor `signatureCipher`** — only segment metadata (`itag`, `initRange`,
   `indexRange`, etc.). This is YouTube's newer **SABR (Server-side Adaptive
   Bitrate)** delivery model: no per-format URL at all, requiring a binary UMP
   protocol + POST-based segment-fetch flow instead. Implemented full
   `signatureCipher`/`n`-parameter decryption via a WebView running the
   community `yt-dlp/ejs` solver (`YtEjsSolver.kt`) on the theory that `WEB`'s
   encrypted formats would still have real URLs to decrypt — but `WEB`'s
   formats turned out to be SABR-shaped too (no cipher to decrypt, nothing to
   feed the solver). Confirmed via diagnostic logging across ~8 different
   tracks: **100% failure rate**, not video-specific.
5. **Exhausted the cheap options**: tried `IOS` and `TVHTML5` client profiles
   as a longshot (`6bdd9cd`) in case some non-web/non-mobile-flagship client
   still got classic direct URLs. Both failed at an earlier stage than SABR:
   `IOS` → `playabilityStatus: UNPLAYABLE`, `TVHTML5` → HTTP redirect loop
   ("Too many follow-up requests: 21"), before ever reaching format
   extraction.

## What we know for certain now

- **All 4 tried client profiles fail** — `WEB`/`ANDROID` are SABR-only (no
  URL, no cipher, nothing to decrypt); `IOS`/`TVHTML5` fail earlier still.
- This is **not a code bug in this app** — the same SABR-only response
  happens on freshly-verified, exactly-matched client field values.
- **yt-dlp itself does not have merged/stable SABR support.** Researched
  directly: the implementation is yt-dlp PR #13515 ("[fd/sabr] Add YouTube
  SABR protocol downloader"), opened 2025-06-21, still open and unmerged as
  of 2026-08-21 (14+ months). Scale: ~227 commits, ~21,500 added lines, 87
  files, 730+ tests — a full new downloader subsystem (binary UMP/protobuf
  parsing, POST-based segment fetch, resumable-download state, fallback
  servers), not a contained extractor tweak. A maintainer called it "a
  massive PR" needing "serious time" to review.
- The only present-day way to get SABR playback at all is a **separate,
  explicitly experimental third-party plugin** (`yt-dlp-ytse` / "YouTube
  Streaming Experiments", same author as the PR), not part of stable yt-dlp.
- SABR rollout is **client/situation-dependent, not yet universal** per
  yt-dlp issues #15689, #15793, #13968, #12482 — `web` is hit hardest, and
  it's expanding progressively rather than being a single flag-day cutover.
  No client currently has a documented, durable exemption.

## Resolution found — client rotation to VISIONOS (2026-08-22, later same day)

The user pointed out that their own local Spotube checkout (`d:/github/spotube`)
was *currently, actively* playing YouTube audio despite everything above.
Investigation found it uses a different engine than expected —
`NewPipeEngine` → `flutter_new_pipe_extractor` → **NewPipeExtractor**
(`org.schabi.newpipe.extractor`, the Java library behind the NewPipe app),
*not* `youtube_explode_dart` (which, independently checked, is fighting the
identical SABR/403 battle right now — issue #386, PRs #388-390 in the last
72 hours — so that path would not have helped).

Checked NewPipeExtractor's actual current source directly. Its strategy is
not clever token generation — it's the same client-rotation whack-a-mole
described above, just one hop ahead: it currently relies on the **`VISIONOS`**
client (the Apple Vision Pro YouTube app), tracked centrally by NewPipe in
`TeamNewPipe/NewPipe#12248`, having already hopped WEB → TVHTML5 → ANDROID_VR
→ VISIONOS as each was SABR-enforced in turn (PR #1508, merged 2026-06-09).

**Verified locally with `curl` before touching the phone** (exact request
shape from NewPipeExtractor's `InnertubeClientRequestInfo.ofVisionOsClient()`:
`youtubei.googleapis.com` host, no API key, random 12-char `t` query token,
minimal headers):
- `/youtubei/v1/player` → `playabilityStatus: OK`, adaptiveFormats with a
  direct `url`, no `signatureCipher`, no `n` parameter at all.
- `curl -H "Range: bytes=0-4095"` against that URL with the matching
  User-Agent → `HTTP 206`, 4096 bytes, no 403.

Implemented as `resolveViaVisionOs()` in `AndroidYouTubeMediaService.kt`,
tried first, falling through to the existing WEB/ANDROID/IOS/TVHTML5 loop on
any failure (commit `cdeee89`). `PlaybackService`'s byte-fetch User-Agent
updated to match. **On-device confirmation still pending** as of this
writing (CI build in flight) — spec doc will be updated to "resolved" once
verified end-to-end on the phone, not just via curl.

**This is explicitly not a durable fix.** It is the same reactive
client-string-spoofing arms race NewPipeExtractor itself is running — expect
`VISIONOS` to eventually get SABR-enforced too, at which point re-check
`TeamNewPipe/NewPipe#12248` and `InnertubeClientRequestInfo.java` for
whatever client they've since hopped to, rather than guessing blind.

## Options considered before the VISIONOS finding (superseded, kept for record)

1. Depend on a Piped/Invidious public instance as a proxy.
2. Run yt-dlp (or its experimental `yt-dlp-ytse` plugin) as our own backend.
3. Switch to a different audio source (e.g. SoundCloud).
4. Wait for yt-dlp's SABR PR to merge or YouTube's rollout to clarify.

All four are superseded by the VISIONOS client-rotation fix above, but remain
relevant as fallback options for whenever `VISIONOS` itself gets enforced.

## Explicitly not pursued

- **Reimplementing SABR/UMP directly in Kotlin.** Assessed and rejected: even
  yt-dlp's large, expert contributor base hasn't finished this after 14+
  months on a ~21k-line subsystem. Not realistic for a solo/small-team
  timeframe. NewPipeExtractor's success confirms this was the right call —
  the actual working fix needed zero SABR/UMP code, just a client swap.
