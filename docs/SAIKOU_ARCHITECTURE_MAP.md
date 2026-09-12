# Saikou architecture map used for KSP Ani

This document records the clean-room behavioral map derived from the supplied Saikou `1.2.5.9b6b6fe` APK. It describes responsibilities and call flow; it does not copy Saikou source code or provider extractor implementations.

## 1. Application domains observed in the APK

The APK contains distinct packages/responsibilities for:

- AniList API, queries, mutations and account state
- home / anime / manga list screens
- media details
- anime parsers and extractors
- manga parsers / manga reader
- novel reader
- player / mpv integration
- player settings
- downloads
- subscriptions
- settings
- external connections such as MAL/Discord

The important lesson for KSP Ani is that these are separate state machines rather than one all-purpose provider service.

## 2. Source registry and source selection

Relevant Saikou concepts observed:

```text
BaseSources
AnimeSources
WatchSources
Selected
```

`Selected` acts as durable per-media source/player preference state. Observed fields/responsibilities include source, preferred dub state, video index, subtitle selection, server selection and current/latest episode state.

Saikou's media-details source change updates the selected source, clears the previous server selection, persists the new `Selected` state, and then loads that source. It does not create a global cross-provider episode list.

### KSP Ani mapping

```text
SourceRegistry
SourceSelection
SourceStateRepository
```

`SourceSelection` is persisted in SQLite by AniList media ID.

## 3. Persistent show matching

Relevant Saikou concepts observed:

```text
BaseParser.autoSearch(Media)
loadSavedShowResponse(mediaId)
saveShowResponse(mediaId, ShowResponse)
ShowResponse
```

The observed `autoSearch` behavior is approximately:

```text
selected parser
  -> check saved ShowResponse for media ID
  -> if no usable saved match:
       use AniList media names
       search parser
       choose result
       persist ShowResponse
  -> use the persisted ShowResponse thereafter
```

Observed debug strings include `Searching :`, `Found :`, and `No results found`.

`ShowResponse` carries source-specific series identity such as name, link, alternate names, episode information/total and provider-specific extra state.

Saikou also has a first-class manual override path through media-details logic. The observed `overrideEpisodes(...)` path saves a chosen response and then loads episodes from that response.

### KSP Ani mapping

```text
SourceSeries
TitleMatcher
source_series_match SQLite table
EpisodeCoordinator.overrideSeriesMatch(...)
Choose Match UI
Rematch Source UI
```

A source-series match is keyed by:

```text
AniList media ID + source ID
```

## 4. Episode loading

Relevant Saikou concepts observed:

```text
WatchSources.loadEpisodesFromMedia(...)
AnimeParser.loadEpisodes(...)
Episode
```

The observed flow is:

```text
loadEpisodesFromMedia(sourceIndex, media)
  -> WatchSources.get(sourceIndex)
  -> parser.autoSearch(media)
  -> obtain ShowResponse
  -> if ShowResponse already carries episodes, map them
  -> otherwise parser.loadEpisodes(show.link, show.extra)
```

Episodes therefore belong to a specific matched show in a specific parser.

### KSP Ani mapping

```text
AnimeSource.loadEpisodes(media, SourceSeries)
SourceEpisode
EpisodeCoordinator.normalize(...)
```

KSP Ani never fabricates one global provider-independent episode object and later tries to rediscover which provider episode it meant.

## 5. Dub/source variants

Saikou's anime source UI persists the preferred dub choice and pushes that preference into the selected parser before reloading episodes.

### KSP Ani mapping

`SourceSelection.languagePreference` is persisted as `AUTO`, `ENGLISH_SUB`, or `ENGLISH_DUB` (with the legacy `preferDub` column retained for migration compatibility). Episode groups, server names, playback labels, and subtitle tracks can all participate in language selection.

## 6. Episode -> video-server resolution

Relevant Saikou concepts observed:

```text
MediaDetailsViewModel.loadEpisodeVideos(...)
AnimeParser.loadByVideoServers(...)
AnimeParser.loadVideoServers(...)
AnimeParser.loadSingleVideoServer(...)
VideoServer
VideoExtractor
Video
Subtitle
AudioTrack
```

The observed player path is not:

```text
episode webpage -> player
```

It is conceptually:

```text
provider episode
  -> provider video servers/extractors
  -> selected/saved server
  -> resolved video list + subtitles/audio
  -> selected video
  -> player
```

Provider-specific extractors are separate from the core app lifecycle.

### KSP Ani mapping

```text
AnimeSource.loadVideoServers(...)
VideoServer
AnimeSource.resolveServer(...)
ResolvedServer
PlaybackSource
SubtitleTrack
AudioTrack
PlaybackResolution
```

The clean built-in providers only resolve direct/local or user-authorized media. KSP Ani does not implement the unofficial streaming extractors found in Saikou.

## 7. Player state

Relevant Saikou player classes observed:

```text
MpvVideoPlayer
PlaybackService
PlaybackState
PlayerActivity
PlayerViewModel
PlayerRepository
PlayerEpisodeUiState
TrackEpisode
TrackParser
PlayerSettings
```

The APK shows player state kept outside source matching. It persists current episode / continue state and current playback position.

Observed player settings include:

- autoplay
- seek time
- default speed
- subtitles
- gestures
- picture-in-picture
- resize
- timestamp/skip options
- watch percentage
- many subtitle/display options

### KSP Ani mapping

```text
PlayerController
PlayerSettings
PlayerSession
PlaybackProgressRepository
playback_progress SQLite table
```

The clean player does not change show matching or source selection while playing.

## 8. Watched threshold and AniList progress

Relevant observed Saikou behavior:

```text
PlayerViewModel.updateAnimeProgress()
PlayerSettings.watchPercentage
UpdateProgressKt.updateProgress(...)
```

The player checks the configured watched percentage, current source episode number and AniList account state before updating progress. Continue state and current position are persisted independently.

Next/previous episode operations also integrate progress update behavior.

### KSP Ani mapping

Default watched threshold:

```text
85%
```

At the threshold, KSP Ani persists watched state and uses `AniListListService.setProgress(...)`. Progress never decreases. Planning/Paused/Dropped transition to Current when a later episode is actually watched; a known final episode can transition to Completed; Repeating is preserved.

## 9. AniList media-list editing

Observed Saikou mutation/edit-list responsibilities include:

- progress
- private
- repeat
- notes
- custom lists
- score
- status
- started date
- completed date
- delete list entry
- favourites elsewhere in the AniList layer

### KSP Ani mapping

`AniListListService` keeps these fields together rather than having playback invent a separate local list model.

## 10. Home and list state

Observed `AnilistHomeViewModel` responsibilities include separate content groups such as:

- anime continue/current
- anime favourites
- anime planned
- manga continue/current
- manga favourites
- manga planned
- recommendations
- genres

### KSP Ani mapping

The clean Home currently presents Continue Watching, Planning, Trending Anime, Popular Anime and Trending Manga, with My List grouped by real AniList statuses.

## 11. Manga / reader architecture observed

Saikou keeps manga parser state and reader state separate from anime/player state. Reader settings observed include direction/layout, dual page, border crop, page numbers, scroll behavior, padding, rotation, true colors, volume-button navigation and webtoon-related behavior.

The clean KSP Ani v1.0 project already keeps Manga as an AniList media type, but a complete source-backed manga reader is intentionally a separate future module rather than being mixed into the anime episode coordinator.

## 12. Downloads observed

Saikou uses a dedicated download service/manager rather than making the player responsible for downloads.

The clean KSP Ani v1.0 player likewise does not own downloads. A future download module should consume a resolved `PlaybackSource` and persist its own download state.

## 13. UI/settings architecture observed

Saikou has separate UI settings for dark mode, compact/small view, default anime/manga view, banner animations, immersive mode, startup tab, home layout and animation speed, plus detailed player/reader settings.

KSP Ani keeps app configuration in `AppConfig` and player-specific settings in `PlayerSettings`, leaving space for dedicated reader/download settings without coupling them to source parsers.

## 14. Why the v0.5.x approach was discarded

The old experimental branch accumulated abstractions that did not match the observed Saikou lifecycle:

```text
provider-independent fabricated episode list
+ global provider availability scan
+ cross-provider resolution on Watch
+ page URLs treated as player input
+ several overlapping "Auto" source services
```

The clean project deliberately removes all of those.

## 15. Clean-room boundary

KSP Ani copies responsibilities, not Saikou code. The APK was used to understand state boundaries and sequencing. Provider-specific unofficial extractors observed in Saikou are not implemented in this clean project.
