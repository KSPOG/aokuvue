# KSP Ani clean sources and references

## Behavioral reference supplied by project owner

- Saikou `1.2.5.9b6b6fe` APK
  - used to map responsibilities and runtime call flow
  - no Saikou branding/assets are included
  - no bundled unofficial streaming extractor implementation is copied into KSP Ani

## AniList

KSP Ani uses AniList GraphQL for metadata, account identity and list state.

Endpoint:

```text
https://graphql.anilist.co
```

OAuth application:

```text
Client ID: 48788
Redirect: https://anilist.co/api/v2/oauth/pin
```

## Java dependencies

Configured by Gradle:

- Java 21
- JavaFX 21.0.8: controls, graphics, media, web
- Jackson Databind 2.18.3
- SQLite JDBC 3.49.1.0
- JUnit Jupiter 5.11.4 for tests

## Playback boundary

Direct-media providers return actual direct/file media URIs through `ResolvedServer` / `PlaybackSource` and use the JavaFX native media player. Web-page providers are explicitly tagged with the `web` container and rendered in JavaFX WebView; their landing pages are never passed to `MediaPlayer` as if they were media files.


## YarrList provider directory

KSP Ani v1.3.0 uses `https://yarrlist.net/anime-list` as a live directory of anime-site domains. The application does not equate directory membership with native playback capability.

A provider enters the Playback Source selector only through an `AnimeSource` implementation that returns actual direct/file media through `ResolvedServer` / `PlaybackSource`.

See `docs/YARRLIST_PROVIDER_MATRIX.md` and `docs/SOURCE_PLUGIN_SDK.md`.


## Internet Archive

The built-in Internet Archive provider uses the public Advanced Search endpoint and Item Metadata API to locate archive items and enumerate directly downloadable MP4/M4V files. No webpage is embedded for playback.


## GogoAnime

The built-in GogoAnime provider targets `https://gogoanime.by`. It uses the site's server-rendered search/series HTML to identify a matched series and enumerate exact provider episode-page links. Playback uses the provider episode page only as a hidden JavaFX resolver. KSP Ani validates the page, isolates its embedded iframe/video player, and reveals only that player surface; it does not expose the provider webpage or an external-browser playback fallback.

The integration was checked against the current public site structure where series pages use `/series/<slug>/` and episode pages use `<slug>-episode-<number>-english-subbed/` or `...-english-dubbed/`. Provider HTML can change, so the parser is isolated in `GogoAnimeSource.java`.
