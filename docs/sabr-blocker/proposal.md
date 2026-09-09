# Change: YouTube audio source blocked by SABR rollout

- Status: **PARTIALLY RESOLVED — real fix found; the Range/CDN theory below
  was WRONG and later retracted.** What actually works today: `VISIONOS` as
  the sole client profile, with `AUTO` no longer falling back to the
  (permanently broken) `ANDROID_VR` (commit `da0e51f`). The "local CDN node
  rejects open-ended Range" theory (commit `e521bf6`) looked confirmed via
  packet capture at the time but did NOT survive a same-day retest — see
  "2026-08-22 late update" at the end of this file for the real story and
  why the section below is kept only as a cautionary tale about a
  convincing-but-wrong root cause.
- Date opened: 2026-08-22, both root causes found and fixed same day
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
timing which profile actually succeeded. (Later simplified to just
AUTO/VISIONOS/ANDROID_VR once the other four were confirmed dead — see the
commit removing them.)

## The real remaining bug: local CDN node rejects open-ended Range continuation

After the `VISIONOS` fix shipped, on-device playback still failed — every
track resolved fine (`stream_resolved` every time, matching the client
matrix above) but audio playback itself 403'd a constant ~3.4-6s after
`playback_prepared`, regardless of `VISIONOS` vs `ANDROID_VR`, WiFi vs mobile
data, or `prefetch` on/off. A `curl` re-test of `ANDROID_VR` from a desktop
machine on the same LAN succeeded cleanly (see the section above), which
looked like an unexplained phone-vs-desktop discrepancy — several wrong
theories were tried and ruled out in order:

1. **UA mismatch** — real bug, found and fixed (commit `bc981ae`,
   `ClientAwareDataSourceFactory` picks UA from the URL's `c=` param), but
   fixing it did **not** resolve the 403s. Wrong/incomplete theory.
2. **Prefetch concurrency** (multiple parallel `stream_resolved` calls
   hammering the same CDN edge) — disabled the `prefetchEnabled` setting
   entirely; 403s persisted identically with strictly sequential,
   one-track-at-a-time resolves. Ruled out.
3. **DNS routing to a bad CDN edge** — checked whether the ISP's DNS
   resolver was giving the phone a different, broken `googlevideo.com` IP
   from the desktop. Compared ISP DNS, `8.8.8.8`, and `1.1.1.1` for the
   same hostname (`rr6---sn-x2pm-f5fs.googlevideo.com`) — **identical IP
   from all three** (`185.225.248.17`). This hostname is a specific CDN
   edge YouTube's `/player` response already commits to server-side;
   client-side DNS resolver choice cannot change which edge it points to.
   Ruled out (and don't suggest changing router DNS for this — it's a dead
   end, confirmed by direct test, not guesswork).

**Actual root cause**, found by capturing the phone's real traffic at the
network layer (SSH into the LAN router `japa55.japa`, `opkg install
tcpdump`, capture `host <phone-ip> and port 443` to a pcap, pull it back
via `ssh router "cat file" > local file` since the router had no
`sftp-server` for normal `scp`, then parse the pcap's raw TCP header bytes
directly in a small Python script — no `tshark`/`scapy` needed) and cross-
referencing with a hand-crafted `curl` reproduction:

- Traffic to real Google-owned IPs (`142.251.x.x` — API/search/base.js)
  was completely normal: long-lived, no drops.
- Traffic to `185.225.248.x` (the specific subnet `rr6---sn-x2pm-f5fs
  .googlevideo.com` resolves to — **this ISP's local Google Global Cache
  node**, not general Google infrastructure) showed dozens of TCP
  connections opened and RST'd within ~60-100ms, repeating throughout the
  whole session.
- Reproduced deterministically with plain `curl` against that exact node:
  - `Range: bytes=0-4095` (bounded) → `HTTP 206`, works.
  - `Range: bytes=0-` (open-ended, no upper bound) → **instant `HTTP 403`**
    (~50ms), every time.
  - No `Range` header at all → also instant `HTTP 403`.
  - Sequence: `Range: bytes=0-65535` (succeeds, 206) immediately followed
    by `Range: bytes=65536-` (open-ended continuation from where the first
    request left off) on the same URL → **the continuation gets instant
    403**, while the exact same continuation expressed as another *bounded*
    range (confirmed earlier the same day: three sequential bounded ranges
    all succeeded) works fine.
- This exactly matches ExoPlayer's own behavior: `DefaultHttpDataSource`
  requests an initial bounded chunk, plays through it (~3.4-6s depending on
  bitrate/buffer config), then issues an **open-ended** continuation
  request for the rest of the file — which this specific ISP-hosted CDN
  node rejects. Not a Google policy change (their own infrastructure never
  showed this behavior in any test) — a quirk/misconfiguration specific to
  this one local cache deployment.

**Fix** (commit `e521bf6`): `ClientAwareDataSourceFactory` (already added
for the UA fix) also rewrites any open-ended `DataSpec` (Media3
`DataSpec.length == C.LENGTH_UNSET`) into a bounded one, computing the
missing upper bound from the `clen` (content length) query parameter Google
already embeds in every signed `videoplayback` URL. Every request this app
makes now has an explicit end byte, so it never hits this node's rejection
path — independent of which client profile resolved the URL, so it will
keep working even if the SABR client-rotation situation above changes

## 2026-08-22 late update: the Range/CDN theory above was wrong

After shipping `e521bf6`, on-device testing still showed the same 403s.
Added per-request diagnostic logging directly in `ClientAwareDataSourceFactory`
(commit `85571c4`/`8426788`/`f7dedd6`) instead of guessing again. Findings,
each one falsifying the previous theory before moving to the next:

1. Even the plain, unranged first request (`boundedLength=-1`, byte-for-byte
   identical to the original pre-`e521bf6` code) still got an instant 403.
   **The Range header was never the cause.**
2. Logged the signed URL's `ip=` parameter against the device's actual
   public IP (via a live `api.ipify.org` check on failure) — identical
   every time. **Not an IP/CGNAT mismatch either.**
3. The actual cause: `AUTO` was silently falling back from `VISIONOS` to
   `ANDROID_VR` whenever `VISIONOS` failed to resolve a specific track.
   `ANDROID_VR`'s `/player` call still reports `playabilityStatus: OK` with
   a seemingly valid URL, but **every** byte-fetch to that URL 403s,
   unconditionally — confirmed independently via `curl` from this dev
   machine (same result, no phone involved). Fixed by removing the
   `AUTO`→`ANDROID_VR` fallback entirely (commit `da0e51f`) — `ANDROID_VR`
   is now selectable only as an explicit Settings override.

**Lesson**: a theory "confirmed" by packet capture + curl reproduction can
still be wrong if the reproduction doesn't carry the same context as the
real failure (in this case: which client profile actually resolved the
URL). On-device, per-attempt logging of the actual HTTP request/response
settled it in minutes; the network-layer investigation the day before,
despite being thorough, chased a coincidental correlation.

### PO-token investigation for `ANDROID_VR` (same day, local spike only)

Hypothesis: `ANDROID_VR`'s CDN-level 403 is Google's PO-token (BotGuard)
enforcement. Tested locally (Node.js, no Android/phone involved):

- Cloned `Brainicism/bgutil-ytdlp-pot-provider`, generated a real,
  correctly-minted PoToken via its jsdom-based BotGuard runner (both
  video-ID-bound and visitor-ID-bound content bindings).
- Attached it to `ANDROID_VR`'s `/player` request
  (`serviceIntegrityDimensions.poToken`): `playabilityStatus` stayed `OK`
  (identical to no token), the returned `videoplayback` URL never got a
  `pot=` parameter, and byte-fetch still 403'd. **`/player` for this client
  appears to ignore the PO-token field entirely** — PO-token is not the
  cause of `ANDROID_VR`'s block, closing that investigation (see
  `spec.md`'s "ANDROID_VR is opt-in only" requirement for the durable
  conclusion).
- Same PoToken tried against the `WEB` client (which, unlike `ANDROID_VR`,
  is documented to actually validate PO-tokens): made things *worse* —
  `playabilityStatus` went from the normal SABR-only `OK` to `UNPLAYABLE`.
  Read as: `WEB` detected the token as invalid/untrusted. Working theory:
  a PoToken minted by running BotGuard's real JS inside `jsdom` (a fake,
  freshly-spun-up DOM with no genuine render pipeline, event history, or
  browser fingerprint) is distinguishable from one minted by a real
  browser, and gets rejected outright rather than silently ignored.
- Follow-up spike in progress (not yet concluded): running the exact same
  BotGuard `program`/interpreter fetched from YouTube inside a genuine
  Chrome tab, driven over raw CDP websockets (no puppeteer/playwright per
  explicit preference), instead of `jsdom` — to test whether a token minted
  in a real browser engine is trusted by `WEB` where the jsdom-minted one
  wasn't. Blocked on locating the actual BotGuard challenge inside the
  homepage's `ytAtN(...)` payload, whose `R` field is a double-JSON-encoded
  string (`attData.R` is itself a JSON string, not an object) — the
  extraction code in `bgutil-ytdlp-pot-provider`'s
  `getChallengeFromHomepage` glosses over this by only using it from inside
  real Node/jsdom where the same parsing subtlety was already handled
  upstream; our from-scratch CDP script needed an explicit `JSON.parse` on
  that nested string. Scratch script: `cdp_pot.mjs` in this session's
  scratchpad — not part of the repo, purely exploratory.
- Also curl-tested three more client profiles while at it, all dead ends:
  `WEB_EMBEDDED_PLAYER` and `TVHTML5_SIMPLY_EMBEDDED_PLAYER` both fail at
  `/player` itself (`status: ERROR`, "This video is unavailable" / error
  code 152-18, reproduced across multiple videos so not video-specific);
  `MWEB` fails with `UNPLAYABLE` / "The page needs to be reloaded" (classic
  stale-or-blocked-client signature). None reach the format-extraction
  stage at all.

Node.js (LTS, via winget) was installed on the dev machine for this spike
and left in place. No app code changed as a result of the PO-token
investigation itself — only the `AUTO`→`ANDROID_VR` fallback removal
(point 3 above) is a shipped fix.

**Follow-up conclusion (same day, CDP spike completed):** minting the
PoToken via a genuine Chrome tab over raw CDP (instead of jsdom) worked —
a real, validly-signed token was produced — but made no difference: `WEB`
still returned `UNPLAYABLE` / "The page needs to be reloaded" identically
*with or without* the token attached, proving PO-token was never the
actual gate for `WEB` either. Sniffing the real browser's own network
traffic for the same video (via CDP `Network.requestWillBeSent`, which
sees plaintext before TLS — no router/packet-capture needed) confirmed
why: real YouTube playback in a browser sends `POST` requests to
`videoplayback` with an **opaque binary payload** (SABR/UMP), to a URL
that has no `itag=`/`pot=`/format query params at all — a completely
different protocol shape than the `adaptiveFormats[].url` + `GET`+`Range`
approach this app (and yt-dlp/NewPipe generally) relies on. A PoToken
alone cannot unlock the old-style response on a client that has fully
moved to SABR; the classic mechanism this app depends on only survives on
whichever client hasn't been SABR-enforced yet (`VISIONOS`, for now).
**Working conclusion, not yet final** — PO-token alone does not explain
either client's failure the way it was first hypothesized: it is
provably not the gate for `ANDROID_VR` (silently ignored) and, on the one
`WEB` test done so far, made no observable difference either
(`UNPLAYABLE` with or without it). But that `WEB` test was a single
request from a script, not a real page load — the CDP network sniff
(same session) showed genuine browser playback going through `POST
videoplayback` with an opaque SABR/UMP binary payload, which is the more
likely explanation for why the classic `adaptiveFormats[].url` shape
never shows up for `WEB` regardless of PO-token. Continuing to use the
now-running Chrome+CDP setup to capture a real page's *full* request
sequence for a specific video (not just the homepage's autoplay preview
requests captured so far) before writing a final conclusion — see next
update below once that capture is done.

**Final capture result**: navigated a real Chrome tab directly to
`https://www.youtube.com/watch?v=dQw4w9WgXcQ` and sniffed every
`videoplayback`/`/player` request via CDP `Network.requestWillBeSent`.
Two distinct kinds of `videoplayback` request showed up:
- Several with `itag=18` and `expire=1512402725` — that expire timestamp
  decodes to **2017-12-04**, i.e. a stale/fixture URL baked into some
  page asset (ad-related or a cached example), not a live request for
  this video; also plain `GET`, no POST body.
- One with a *current* `expire` (2026), no `itag=` param at all, and a
  binary `POST` body (SABR/UMP) — the actual, live request this specific
  video's real playback used.
This is now confirmed twice (homepage autoplay previews, and now a full
direct video load): **real browser playback of current YouTube video is
SABR/UMP end-to-end, with no classic-shaped request anywhere in the real
path.** PO-token is irrelevant to why the classic `adaptiveFormats[].url`
approach fails for `WEB` — the classic approach itself isn't used by real
clients anymore, PO-token or not. This closes both the PO-token
investigation and the "why does `WEB` still not work" question:
implementing SABR/UMP (a genuinely large undertaking, not attempted here)
is the only way to use `WEB`-class clients again; until/unless that
happens, `VISIONOS` (and, if it ever comes back, `ANDROID_VR`) are the
only viable profiles precisely because they're the ones YouTube hasn't
yet forced onto SABR.
again later.
