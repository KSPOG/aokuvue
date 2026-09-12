# Clean rebuild design

## Core invariant

A selected episode must remain owned by the same source from series match through playback resolution.

```text
SourceSeries.providerId
 == SourceEpisode.providerId
 == VideoServer.providerId
 == PlaybackResolution.source.id
```

The application must never resolve an Episode 12 object from Source A by asking Source B what Episode 12 means.

## State ownership

### AniListClient / AniListListService

Own remote AniList identity, metadata and list state.

### SourceStateRepository

Owns only source-related persistent state:

- per-media source selection
- saved server/video/subtitle preference
- per-media/per-source matched series

### AnimeSource

Owns provider-specific behavior:

```java
search(media)
loadEpisodes(media, series)
loadVideoServers(media, series, episode)
resolveServer(media, series, episode, server)
```

### EpisodeCoordinator

Owns orchestration and invariant enforcement, but no provider HTTP/site logic.

### PlayerController

Owns resolved playback state, resume state and watched-threshold behavior. It never searches sources.

## Error policy

- A source search failure does not corrupt an existing saved source match.
- Source switch resets saved server preference for that media.
- Rematch removes only that media/source match.
- Refresh reuses the saved match.
- Stale asynchronous episode results cannot replace the current detail page result.
- A tracking-only source cannot enter the player.
- A resolved server with no videos fails before player creation.
- A video requiring unsupported arbitrary headers fails explicitly rather than opening a webpage.

## Extensibility

Optional source JARs implement `AnimeSourcePlugin` and register via `ServiceLoader`.

This is deliberately narrower than giving plugins access to the whole UI/database. It prevents source-specific code from replacing the app's tracking/player lifecycle.

## Future modules

These should remain separate from `EpisodeCoordinator`:

- manga chapter coordinator
- reader state
- downloads
- timestamp/intro skip provider
- offline metadata cache
- notification/subscription service
