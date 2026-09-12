# KSP Ani Clean v1.5.12

## Current source status

All episode and playback provider integrations have been removed. The application starts with an
empty playback registry. All 93 EverythingMoe Anime Streaming entries are loaded as external
sources, with the highest-ranked live entry selected automatically. These entries open through
KSP Ani's built-in source browser, where the provider's own episode browser and player can be used
without leaving the application. Native stream controls still require a dedicated provider adapter
because EverythingMoe itself does not expose an episode or video API. AniList catalog/list tracking
remains active.

The older sections below are retained as historical release notes and do not describe the current
provider configuration.




## v1.5.12 PlayerController lambda compile fix

- Fixes `PlayerController.java:68: local variables referenced from a lambda expression must be final or effectively final`.
- After relay selection, the final resolved URI is copied to immutable `resolvedMediaUri`.
- JavaFX `Media` creation and the asynchronous media-error callback now reference the same immutable URI.
- No playback behavior from v1.5.10 is otherwise changed.

## v1.5.10 Java text-block escape compile fix

- Fixes the `MainWindow.java` compile failure at the GoogleVideo resource-timeline regex.
- The Java text block uses a Java-safe escaped slash while still emitting the intended JavaScript regex.
- No playback behavior from v1.5.9 is otherwise changed.
## v1.5.9 GoogleVideo relay fix

- Uses one exact Chromium-style User-Agent for GogoAnime discovery and the signed media relay.
- Captures accessible player cookies and forwards them to the upstream media host.
- Removes the synthetic Origin header from media requests while preserving the verified player Referer.
- Binds the local relay explicitly to IPv4 `127.0.0.1`.
- Gives the relay a real media path such as `/media.mp4` and forces `video/mp4` for Blogger/GoogleVideo signed MP4 URLs.
- Synthesizes JavaFX HEAD metadata from an upstream `Range: bytes=0-1` request so total length and byte-range support are available without relying on CDN HEAD behavior.
- Uses HTTP/1.1 for GoogleVideo/Blogger relay traffic and preserves `206`, `Content-Range`, `Accept-Ranges`, ETag and Last-Modified.
- Finds `/videoplayback` URLs in the WebView resource timeline even when they do not end with `.mp4`.
- Adds `[KSP Ani][MediaRelay]`, `[KSP Ani][Media]`, and `[KSP Ani][MediaPlayer]` console diagnostics for the next runtime check.
- Keeps wrong-series validation, fit-to-window, autoplay, Previous/Next and automatic server failover.

## v1.5.8 GoogleVideo relay introduction

- Fixes JW Player **Error 224003** in the embedded WebView by no longer asking WebView to decode the final video stream.
- KSP Ani now uses WebView only as an invisible resolver: verified episode page -> embedded player host -> real HLS/MP4 URL.
- The resolved HLS/MP4 URL is handed to the native JavaFX `MediaPlayer`/`MediaView`, which already fits the KSP Ani player area and honors autoplay/resume settings.
- Embedded player-host navigation is performed from inside the current provider document so normal browser referrer/cookie context is preserved while resolving.
- If the native stream fails, KSP Ani automatically tries the next source/server variant instead of exposing the provider page.
- Provider page identity now uses only primary metadata (document title, OG/Twitter title, first H1, canonical/path). Recommendation/sidebar H2 text can no longer validate the wrong anime.
- Saved GogoAnime matches are revalidated on restore. Contradictory records such as a Mushoku title saved against a `grand-blue-season-3` provider slug are cleared and rematched automatically.

- Signed `googlevideo.com/videoplayback` URLs are now recognized even without a `.mp4` suffix.
- GogoAnime direct streams are relayed through localhost with the embedded-player Referer/User-Agent and HTTP byte-range support.
- This avoids JavaFX WebKit repeatedly logging `could not connect to media` for Blogger/GoogleVideo sources.

## v1.5.6 embedded-player context fix

- Restores the working in-page iframe model: the provider player is no longer navigated as a new top-level WebView page.
- Fits the existing iframe/video to the KSP Ani player viewport with all document scrolling suppressed.
- Preserves provider cookies/referrer state required by some embedded video hosts.
- Adds verified direct `/series/` probes before the slower GogoAnime search-page fallback.
- Direct probes are never trusted blindly: series identity and the exact episode are verified before playback.
- Keeps automatic playback attempts and popup blocking.

## v1.5.5 provider-native hidden discovery

- Fixed the `Embedded playback wrong series page; all source variants were tried` loop caused by v1.5.4's title-to-slug guessing fallback.
- Lazy GogoAnime playback no longer guesses `/slug-episode-N...` URLs at all.
- If normal HTTP indexing is unavailable, KSP Ani now opens GogoAnime's own search page **invisibly**, extracts real provider `/series/` results, and matches those results against the AniList English/Romaji titles.
- Explicit sequel/installment entries are fail-closed: a Season 3 / III AniList entry will not accept an unnumbered base-series result or a different numbered season.
- After a verified series is chosen, KSP Ani loads that series page invisibly and obtains the exact requested episode URL from the provider's own episode links.
- Only after the episode page passes anime + episode verification does KSP Ani isolate and reveal the embedded player.
- Provider body/recommendation text is no longer used as series identity evidence; identity comes from title/OG title/H1/H2 to avoid recommendation-list false positives.
- Search and episode links are allowed a short DOM-settle retry window before the next search alias is tried.
- The v1.5.3 fit-to-window, scrollbar suppression, popup blocking, and autoplay behavior remains intact.


## v1.5.2 embedded-player-only playback

- Provider webpages are never shown in the player UI.
- KSP Ani loads the episode page invisibly only long enough to validate the anime/episode and locate its embedded player.
- The detected iframe/video is moved into a full-size in-app player surface and every other provider-page element is hidden.
- Popups, provider context menus, and the external-browser playback button are disabled.
- If the player iframe is injected late, KSP Ani waits/retries while keeping the provider page hidden.
- If isolation fails, KSP Ani tries the next source/server instead of exposing the website.


## v1.5.1 wrong-series playback safety fix

- GogoAnime no longer creates a playable series from a guessed slug when the provider did not return a real title match.
- Saved synthetic GogoAnime matches created by v1.5.0 are rejected automatically so the episode coordinator clears and rematches them.
- Title matching is now installment-aware. Explicit `Season 2` / `II` results are strongly penalized for a `Season 3` / `III` AniList entry instead of being accepted because the rest of the title is similar.
- After a GogoAnime page loads, KSP Ani performs a final page-identity check using the page title and H1/H2 headings. A page for another anime is rejected even if it contains the requested episode number and an embedded player.
- When GogoAnime has no verified match, KSP Ani fails over to another real playback source or the internal AniList episode index instead of opening unrelated content.

## v1.4.0 GogoAnime source + episode-mapping fix

- Added **GogoAnime (`https://gogoanime.by`)** as a selectable playback source.
- GogoAnime matching uses the provider's server-rendered series/episode pages and keeps the provider's own episode numbers; it does not invent episode ordering.
- Historical v1.4.0 behavior: GogoAnime originally displayed the provider episode page in JavaFX WebView. **v1.5.5 supersedes this** with hidden resolution plus an embedded-player-only surface.
- GogoAnime is preferred ahead of Internet Archive for new automatic source selection when no explicit default has been saved. Existing per-title source selections are preserved.
- Internet Archive no longer assigns episode numbers by MP4 file order. Unnumbered trailers/promos/preview files are ignored, fixing the case where a trailer could become Episode 1.
- Internet Archive recognizes explicit `S01E01`, `1x01`, `Episode 1`, `Ep 1`, `E01`, and leading-number filename patterns.

## v1.3.1 playback-startup fix

- Internet Archive episodes now use the concrete `d1`/`d2` archive storage hosts returned by metadata instead of always starting through the redirecting `archive.org/download` URL.
- The canonical archive URL remains as a final fallback.
- If an Internet Archive mirror never reaches READY/PLAYING within 8 seconds, KSP Ani automatically tries the next mirror.
- Player status now distinguishes opening, starting, playing, buffering, paused, halted, and errors instead of leaving the old `Resolving Episode…` message on screen.
- Stale startup timers are generation-guarded so navigating to another episode cannot revive an older playback attempt.

## v1.3.0 highlights

- Anime details now show a **Season** dropdown only when AniList resolves two or more related seasons of the same TV/ONA series. Single-season titles do not show the control.
- Switching seasons loads that AniList season as its own media entry, including its own source match, episode list, AniList progress, and playback state.
- Playback language is now **Auto / English Sub / English Dub** per anime.
- English Sub prefers provider sub variants and automatically selects an English subtitle track when one is returned.
- English Dub prefers dub-tagged episode/server/video variants and leaves external subtitles off by default.
- The native player now includes a subtitle selector and basic SRT/WebVTT rendering for provider-returned subtitle tracks.


This is the clean-room KSP Ani desktop rebuild. It is **not** an update layered over the v0.5.x source tree.

The project was restarted around the responsibilities observed in the supplied Saikou 1.2.5.9b6b6fe APK: AniList media state, persistent per-media source selection, persistent per-source show matching, provider-owned episode lists, server resolution after episode selection, player resume state, and AniList progress synchronization.

## Important installation rule

Extract this release into a **new directory**. Do not copy it over the old `D:\AnimeApp` tree.

Recommended:

```text
D:\KSPAni-Clean
```

Then run:

```bat
SETUP_WINDOWS.bat
RUN.bat
```

The old v0.5.x classes and settings are intentionally not part of this tree.

## Clean episode lifecycle

```text
AniList media
    |
    v
per-media SourceSelection
    |
    v
selected AnimeSource
    |
    +--> load saved SourceSeries match
    |        or
    +--> source.search(media) -> rank title/aliases -> save SourceSeries
    |
    v
source.loadEpisodes(media, matchedSeries)
    |
    v
SourceEpisode list owned by that source
    |
    v
click an episode
    |
    v
source.loadVideoServers(...)
    |
    v
saved server, or first available server
    |
    v
source.resolveServer(...)
    |
    v
ResolvedServer -> PlaybackSource
    |
    v
KSP Ani in-app player
    |
    +--> resume position
    +--> watched threshold
    +--> AniList progress sync
```

There is no global source scan, no fabricated cross-provider episode list, and no provider hopping after an episode is selected. Direct-media sources use the native player. Web-backed sources such as GogoAnime use an invisible WebView only to resolve and verify the provider page. When an HLS/MP4 stream is exposed, KSP Ani hands it to the native JavaFX media player instead of displaying the provider webpage.

## Built-in sources

### Local Library

Searches configured local folders for matching anime directories, loads actual local episode files, and resolves them to file URIs for the in-app player.

### GogoAnime

A web-backed source for `https://gogoanime.by`. KSP Ani searches provider series pages and uses the matched episode page only as a hidden in-app resolver. After validating the requested anime/episode, it follows the embedded player invisibly, extracts a real HLS/MP4 source, and hands that stream to KSP Ani's native player. Provider webpage chrome and external-browser playback are not exposed.

### Internet Archive

A public direct-media adapter using Internet Archive's documented Advanced Search and Metadata APIs. It searches public archive items and exposes directly downloadable MP4/M4V files to the KSP Ani player when a confident title match exists.

### Authorized HTTP

A generic source adapter for an API the user owns or is authorized to use. The API contract mirrors the Saikou parser stages:

```text
/search
/series/{id}/episodes
/episodes/{id}/servers
/servers/{id}/resolve
```

See `docs/AUTHORIZED_SOURCE_API.md`.

### Internal AniList episode index

AniList is tracker/index infrastructure only. It does **not** appear in the Playback Source selector. When no direct provider can load a title, the internal index keeps episode numbering and AniList progress usable without pretending AniList is a video source.

## Optional source plugins

Additional providers can be delivered as JARs implementing:

```text
app.kspani.source.AnimeSourcePlugin
```

and registered using Java `ServiceLoader`. KSP Ani v1.3.0 also scans drop-in JARs from:

```text
%APPDATA%\KSPAni\clean-v1\source-plugins\
```

The plugin only creates `AnimeSource` implementations. KSP Ani owns the source selection, saved show match, episode normalization, playback selection, player and AniList synchronization.

## EverythingMoe provider discovery

The app refreshes the ranked Anime Streaming directory at `https://everythingmoe.com` in the background and shows it in Settings. Ranked entries are recommendation metadata; they only appear as Playback Sources if a compatible direct `AnimeSource` plug-in is installed. When an anime is selected, the detail view shows the chosen source immediately and upgrades it to a verified recommendation only after the provider returns a safe series match and episode list. Provider webpages are not embedded in the app.

## AniList

Built-in public OAuth client ID:

```text
48788
```

Registered redirect URL:

```text
https://anilist.co/api/v2/oauth/pin
```

Login opens the user's normal browser. The app never embeds a client secret.

The clean AniList layer supports:

- Viewer/account profile
- Home/discovery queries
- anime and manga search
- media details
- complete Anime/Manga list collection
- Watching / Reading
- Planning
- Completed
- Paused
- Dropped
- Rewatching / Rereading
- progress updates
- score
- repeat count
- priority
- notes
- private status
- custom lists
- start/completion dates
- list deletion API

## Player

The clean player uses JavaFX MediaPlayer and accepts only an actual direct/file media URI returned by a source resolver.

Implemented state:

- autoplay setting
- play/pause
- seek forward/back
- seek bar
- volume
- fullscreen
- previous/next episode
- saved server selection + Change Server
- quality selection
- playback speed selection
- resume position
- periodic progress persistence
- configurable watched threshold, default 85%
- manual Mark Watched
- automatic AniList update after watched threshold

A direct source requiring arbitrary request headers is rejected by this JavaFX player rather than silently falling back to a webpage.

## UI

The current clean UI includes:

- Home
- Anime
- Manga
- My List
- AniList search
- account/login
- rich media detail information
- AniList status/progress controls
- source selection
- persisted automatic show matching
- Refresh Episodes
- Rematch Source
- Choose Match
- episode list
- in-app player
- player/settings controls

The layout is KSP Ani branding. Saikou/Dantotsu are architecture and workflow references only.

## Build

Requires a full Java 21 JDK.

```bat
BUILD.bat
```

Portable app image:

```bat
PACKAGE_WINDOWS.bat
```

Output:

```text
dist\KSPAni\
dist\KSPAni-portable.zip
```

## Runtime data

```text
%APPDATA%\KSPAni\clean-v1\app.properties
%APPDATA%\KSPAni\clean-v1\kspani.db
```

The clean runtime state is deliberately isolated under `clean-v1`; existing v0.x settings/database files under `%APPDATA%\KSPAni` are not read by this build.

## Architecture documentation

- `docs/SAIKOU_ARCHITECTURE_MAP.md`
- `docs/CLEAN_REBUILD_DESIGN.md`
- `docs/AUTHORIZED_SOURCE_API.md`
- `docs/SOURCE_PLUGIN_SDK.md`
- `docs/YARRLIST_PROVIDER_MATRIX.md`
- `BUILD_VALIDATION.md`
- `references/saikou_inventory_summary.md`
