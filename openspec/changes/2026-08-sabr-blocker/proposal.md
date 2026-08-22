# Change: YouTube audio source blocked by SABR rollout

- Status: **RESOLVED (mitigated) — `VISIONOS` client profile confirmed
  working on-device**, commit `cdeee89`. Not a durable fix — see
  "Explicitly not pursued" / rotation risk below.
- Date opened: 2026-08-22, confirmed working same day
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
- **Puzzling counter-evidence found later the same day**: re-tested
  `ANDROID_VR` directly with `curl` from a desktop/dev machine (not the
  phone) using the exact same client fields that failed all day on-device
  — and it succeeded cleanly: `playabilityStatus: OK`, direct `url` (not
  SABR-shaped), no `n` param, and three independent `curl` connections to
  the same signed URL (including one after a 4s gap, simulating ExoPlayer's
  post-buffer reconnect) all returned `HTTP 206` with zero 403s. This
  directly contradicts the persistent on-device 403s logged earlier in
  this same investigation for the same client. Not root-caused — candidate
  explanations: (a) YouTube's SABR/PO-token enforcement is time-windowed or
  was briefly rolled back, (b) something specific to the phone's network
  path (its IP, DNS resolution, or the particular Google CDN edge/PoP it
  gets routed to) differs from this dev machine's, or (c) ExoPlayer's
  actual request pattern differs from a plain `curl -H Range` in some way
  not replicated here (headers, connection reuse behavior, etc.). Left
  unresolved — `VISIONOS` was verified as a working fix by the same
  curl-first method and shipped instead of chasing this further, but if
  `VISIONOS` also mysteriously fails on-device despite curl succeeding,
  this discrepancy point should be revisited first.
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
updated to match.

**Confirmed working on-device** (2026-08-22, same day): multiple different
tracks resolved and played back-to-back with zero `playback_failed`/
`resolution_failed` errors in the logs after installing the build containing
`cdeee89`. Playback is restored.

Also worth recording: a `curl` re-test of the *old* `ANDROID_VR` client (the
one that failed all day on-device) succeeded cleanly from a desktop machine
around the same time — playable URL, three independent connections to the
same signed URL all returning HTTP 206, no 403. This directly contradicts
the on-device 403s logged earlier for the same client and was **not**
root-caused (candidate causes: time-windowed enforcement, phone-specific
network path, or a request-pattern difference between curl and ExoPlayer).
Left as an open, unresolved discrepancy since `VISIONOS` was independently
confirmed working and shipped — see the earlier section in this doc for
detail if this needs revisiting later.

**This is explicitly not a durable fix.** It is the same reactive
client-string-spoofing arms race NewPipeExtractor itself is running — expect
`VISIONOS` to eventually get SABR-enforced too, at which point re-check
`TeamNewPipe/NewPipe#12248` and `InnertubeClientRequestInfo.java` for
whatever client they've since hopped to, rather than guessing blind.

**The "ANDROID_VR curl-works-but-phone-403s" discrepancy above turned out to
be a real code bug, not a network mystery** (found 2026-08-22, same day, via
the explicit per-profile Settings toggle added below): `PlaybackService`'s
byte-fetch User-Agent was hardcoded to `VISIONOS_AGENT` regardless of which
client profile actually resolved the stream URL. This is the *exact* same
UA-alignment bug already root-caused and fixed twice earlier this session
(commits `1d8d1a5` and `6257a5b` — see the debugging timeline above) — it
kept coming back because each new client-profile addition re-hardcoded a
single UA constant instead of deriving it dynamically. Forcing `ANDROID_VR`
via the new setting exposed it immediately: resolve always succeeded
(`stream_resolved` every time) but playback consistently 403'd ~3.4s in,
exactly the old symptom. Fixed properly this time (commit `bc981ae`):
`ClientAwareDataSourceFactory` wraps the upstream HTTP data source and reads
the `c=` query parameter Google embeds in every signed `videoplayback` URL
(e.g. `&c=ANDROID_VR&`, `&c=VISIONOS&`) to pick the matching User-Agent per
request — correct regardless of which profile AUTO falls back to or which
one is force-selected in Settings, with no separate state to keep in sync.

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

## Full curl-tested client matrix (2026-08-22, from a desktop host — not the phone)

Tested all 6 profiles directly against `/youtubei/v1/player` with the exact
field values used in code, right before shipping the AUTO reorder below:

| Profile | Playability | Format shape |
|---|---|---|
| `VISIONOS` | OK | direct `url` — confirmed byte-fetch too (HTTP 206) |
| `ANDROID_VR` | OK | direct `url` — confirmed byte-fetch too (HTTP 206) |
| `ANDROID` | OK | SABR-shaped (no `url`) |
| `IOS` | OK | SABR-shaped (no `url`) |
| `WEB` | UNPLAYABLE | — |
| `TVHTML5` | UNPLAYABLE ("The page needs to be reloaded.") | — |

Only `VISIONOS` and `ANDROID_VR` are currently usable. The `AUTO` fallback
order was reordered to `VISIONOS → ANDROID_VR → WEB → ANDROID → IOS → TVHTML5`
(commit after `1ad406c`) so the two working profiles are tried first,
instead of wasting two failed requests (`WEB`, `ANDROID`) before reaching
`ANDROID_VR` third. `resolveViaVisionOs()` handles `VISIONOS` specially
(different endpoint/host/headers, see above); the rest share the generic
per-profile loop in `resolveStream()`.

An explicit `YouTubeClientProfile` setting (AUTO/VISIONOS/ANDROID_VR/ANDROID/
WEB/IOS/TVHTML5) was added in Settings so a specific client can be forced
(no fallback) for manual diagnosis, rather than only inferring from log
timing which profile actually succeeded.
