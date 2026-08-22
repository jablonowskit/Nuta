# youtube-audio-source

## Purpose
Resolve a playable audio stream URL for a given track by searching YouTube and
extracting an audio format from its unofficial `/youtubei/v1/player` (InnerTube)
endpoint, without any official YouTube SDK or API key. Two independent
implementations exist: `AndroidYouTubeMediaService` (androidApp) and
`NutaYouTubeMediaService` (composeApp/desktopMain) — they are not shared code
and can drift.

## Requirements

### Requirement: Candidate search
The system SHALL search YouTube for a track by artist + title (plus a
"lyrics" variant query), rank candidates by title/artist/duration match and
official-audio/topic-channel signals, and pick the highest-scoring candidate.

#### Scenario: Track has an exact official-audio match
- WHEN a search for "<artist> <title> official audio" returns a video from a
  "<artist> - Topic" channel with a duration within 3s of the track's duration
- THEN that candidate SHALL score highest and be selected first

### Requirement: Multi-client stream resolution with fallback
The system SHALL try more than one YouTube InnerTube client profile in order
(currently `WEB`, `ANDROID`, `IOS`, `TVHTML5`) and fall through to the next
profile when playability is not `OK` or no usable audio format can be
extracted, rather than failing on the first profile tried.

#### Scenario: Preferred client fails, fallback succeeds
- WHEN the first client profile's playability status is not `OK`, or its
  adaptiveFormats yield no usable audio format
- THEN the system SHALL try the next configured profile before giving up

#### Scenario: All profiles exhausted
- WHEN every configured profile fails to yield a usable format
- THEN `resolveStream` SHALL throw with the last playability status seen,
  and the caller SHALL surface this as a resolution failure (not a silent
  empty result)

### Requirement: Client field fidelity
Each non-`WEB` client profile's `context.client` fields (clientVersion,
device/OS fields, User-Agent) SHALL match a currently-valid combination — an
incorrect or stale `clientVersion` causes YouTube to reject the request
outright with HTTP 400 before any playability/format data is returned.

#### Scenario: Stale client version
- WHEN a client profile's `clientVersion` no longer matches what YouTube's
  backend currently accepts for that `clientName`
- THEN the `/youtubei/v1/player` call SHALL fail with HTTP 400, distinct from
  a playability rejection (which still returns a normal JSON body)

### Requirement: Encrypted-signature and throttling-parameter handling
When an adaptiveFormat entry has no direct `url` but has a `signatureCipher`
field, the system SHALL decrypt it (extracting `url`, `s`, `sp` from the
cipher's query-string encoding, solving `s` into a signature, and appending it
under the `sp` key) before the format is usable. When a resolved URL contains
an `n` query parameter, the system SHALL solve it and substitute the solved
value before the format is usable, since an unsolved `n` is expected to result
in throttled/rejected delivery.

#### Scenario: WEB client format has signatureCipher
- WHEN an adaptiveFormat entry has `signatureCipher` but no `url`
- THEN the system SHALL solve the `s` challenge and construct
  `<inner url>&<sp>=<solved signature>` as the final playable URL

- Solving is delegated to `YtEjsSolver` (Android): a headless WebView loads the
  pinned, hash-verified `yt-dlp/ejs` JS bundle (release `0.8.0`) and evaluates
  the same `jsc({...})` call contract that `youtube_explode_dart` (used by
  Spotube) uses against a Deno process — run here via
  `WebView.evaluateJavascript` instead, since the bundle has no Deno/Node
  dependency and is pure browser JS.
- `base.js` (the player script referenced by the watch page's `jsUrl`) is
  fetched once and cached per `AndroidYouTubeMediaService` instance lifetime,
  since it only changes when YouTube rolls out a new player version.

### Requirement: Byte-fetch User-Agent alignment
The HTTP client that actually downloads audio bytes (Media3's
`DefaultHttpDataSource` on Android, `mpv --user-agent` on desktop) SHALL use
the same User-Agent string as whichever client profile's request minted the
resolved URL. A mismatched User-Agent at byte-fetch time results in an HTTP
403 from Google's CDN even though the URL itself is otherwise valid.

#### Scenario: Byte-fetch UA does not match the resolving client
- WHEN the resolving client was `ANDROID` but the player's HTTP data source
  uses a browser User-Agent (or vice versa)
- THEN Google's CDN SHALL reject the byte-fetch request with HTTP 403

**Implementation note (learned the hard way, three times, in August 2026):**
do not hardcode a single UA constant for byte-fetch — every time a new
client profile was added and the UA constant wasn't updated to match, this
requirement broke silently (persistent 403s that looked like a new,
unrelated problem each time). The durable fix (Android, commit `bc981ae`):
derive the UA per-request from the `c=` query parameter Google embeds in
every signed `videoplayback` URL (e.g. `&c=ANDROID_VR&`), via a wrapping
`DataSource.Factory`. This needs no state synchronized with the resolver and
is correct regardless of which profile actually won a fallback race or was
force-selected by the user.

### Requirement: No duplicate connections to the same signed URL
The system SHALL make exactly one HTTP connection per signed stream URL for
the full lifetime of that URL. A second, independent connection to the same
signed URL (e.g. a separate pre-fetch/cache-warming request ahead of the
player's own request) SHALL be avoided, since Google's CDN can reject a
second connection to an already-used signed URL as suspicious.

#### Scenario: Pre-fetch opens its own connection ahead of playback
- WHEN a pre-fetch mechanism opens an independent HTTP connection to a signed
  URL before ExoPlayer/mpv requests the same URL for real playback
- THEN the second (real playback) request SHALL be at risk of HTTP 403,
  independent of client profile or User-Agent correctness

### Requirement: ANDROID_VR is opt-in only, never an AUTO fallback
The `ANDROID_VR` client profile's `/player` endpoint still returns
`playabilityStatus: OK` with an apparently valid signed `videoplayback` URL,
but that URL is unconditionally rejected with HTTP 403 on the very first
byte-fetch request — confirmed on 2026-08-22 to be independent of Range
header shape (bounded vs open-ended vs no Range at all), User-Agent, and
client IP (verified identical between the signed URL's `ip=` parameter and
the device's actual public IP). This is consistent with YouTube's PO-token
enforcement for this client (tracked informally since 2026-08-17). The
`AUTO` client-profile setting SHALL NOT fall back to `ANDROID_VR` when
`VISIONOS` fails to resolve a track — that fallback silently sent some
fraction of tracks through a profile with a 100% byte-fetch failure rate,
which looked like an intermittent/environmental bug (Range shape, ISP CDN
node, IP mismatch) for most of a day before the actual cause (which
profile resolved the track) was identified by logging `videoId`-level
resolution details. `ANDROID_VR` SHALL remain selectable only as an
explicit, user-chosen Settings override, for future debugging.

#### Scenario: VISIONOS fails to resolve a specific track under AUTO
- WHEN `resolveViaVisionOs` returns null for a track (no usable format) and
  the active profile setting is `AUTO`
- THEN resolution SHALL fail for that track (surfaced as a normal resolve
  failure, letting the queue skip to the next track) rather than falling
  back to `ANDROID_VR`

### Requirement: Diagnose actual on-device HTTP behavior before re-theorizing
When a 403 (or other HTTP failure) persists across a code change intended
to fix it, the system's byte-fetch data source SHALL be able to log the
exact request shape (position, requested length, computed Range) and the
exact response (status code, message) for every attempt, rather than
relying solely on external reproduction (curl, packet capture) that may
not share the failing request's exact context (which client profile
resolved it, which specific video, timing).

#### Scenario: A plausible root-cause theory doesn't hold up on retest
- WHEN a fix for a previously-diagnosed root cause is deployed and the
  failure still reproduces
- THEN on-device request/response logging SHALL be added (or already be
  present) so the next test cycle produces evidence instead of another
  guess

## Resolution history
See `openspec/changes/2026-08-sabr-blocker/proposal.md` for the full
debugging narrative (2026-08-22). The investigation went through several
incorrect theories before finding the real cause:
- SABR blocking WEB/ANDROID/IOS/TVHTML5 (confirmed real, led to adopting
  VISIONOS/ANDROID_VR).
- A local ISP CDN node supposedly rejecting open-ended Range continuations
  (looked confirmed via packet capture + curl at the time, but did not
  survive a same-day retest — a fresh curl reproduction of the identical
  signed URL succeeded even for an open-ended Range, and the shipped fix
  made the on-device failure worse, not better, since it added a Range
  header to the position-0 request that previously had none).
- A signed-URL-vs-actual-IP mismatch (ruled out directly: on-device logging
  showed the signed URL's `ip=` parameter and the device's actual public IP
  were identical on every failing request).
- The real cause: `AUTO`'s silent fallback from `VISIONOS` to the
  already-broken `ANDROID_VR` for whichever tracks `VISIONOS` didn't
  resolve — invisible without per-attempt logging, since `ANDROID_VR`'s
  `/player` call still reports `playabilityStatus: OK`.
