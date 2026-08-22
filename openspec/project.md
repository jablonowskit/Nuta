# Nuta — Project Context

## What this is
Kotlin Multiplatform music player. Spotify supplies metadata (search, playlists,
liked songs) via the Spotify Web API; actual audio playback comes from YouTube,
scraped through its unofficial internal "InnerTube" API (no official YouTube
SDK, no YouTube Data API key). Android uses Media3/ExoPlayer for playback;
desktop uses `mpv` over JSON IPC. OAuth for Spotify is explicitly excluded by
project decision — desktop auth goes through a WebView2 helper capturing the
`sp_dc` cookie; Android is the primary target platform (small, battery-light
app: Kotlin + Compose + Media3 + system WebView).

## Modules
- `:composeApp` — Kotlin Multiplatform shared code (`commonMain`, `androidMain`,
  `desktopMain`). UI, playback abstractions, Spotify client, desktop YouTube
  resolver (`NutaYouTubeMediaService`), desktop player (`MpvAudioPlayer`).
- `:androidApp` — Android-specific glue: `Media3AudioPlayer`, `PlaybackService`
  (MediaSessionService), `AndroidYouTubeMediaService` (Android's own InnerTube
  resolver, separate from the desktop one), `YtEjsSolver`.

## Conventions
- Every code change: commit with descriptive message + `Co-Authored-By: Claude
  Opus 5 (1M context) <noreply@anthropic.com>` footer, push to `main`, build via
  GitHub Actions CI, deploy to a physical Samsung Android phone via
  `python scripts/download-latest-apk.py --install` or `gh run` polling +
  `python scripts/deploy-android.py --apk <path>`.
- No local Android SDK — Android-only files can't be compiled locally; sanity
  checks run via `:composeApp:compileKotlinDesktop` (shared code only) plus
  manual diff review for Android-only files.
- The top-right label in the app bar shows `v<version> · <git-sha>` so an
  installed build can be confirmed against the exact commit it came from.

## Known fragile area
YouTube's InnerTube scraping is inherently adversarial — YouTube changes client
requirements, throttling, and URL-signing schemes over time with no notice or
stability guarantee. See `openspec/specs/youtube-audio-source/spec.md` for the
current state and `openspec/changes/2026-08-sabr-blocker/` for the active,
unresolved blocker as of 2026-08-22.
