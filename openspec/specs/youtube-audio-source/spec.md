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

## Known limitation (unresolved as of 2026-08-22)
See `openspec/changes/2026-08-sabr-blocker/proposal.md`. As of this date, all
four configured client profiles fail to yield a usable audio format for every
track tested: `WEB`/`ANDROID` return SABR-shaped adaptiveFormats entries with
neither `url` nor `signatureCipher` at all (a binary UMP/POST-based delivery
protocol this codebase does not implement), and `IOS`/`TVHTML5` fail earlier,
at the playability-status or connection level. **No requirement above is
currently satisfiable end-to-end** — the spec describes the intended/previously
-working behavior, not the current runtime reality.
