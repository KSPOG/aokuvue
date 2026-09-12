# Saikou APK inventory summary

Source inspected locally: `Saikou 1.2.5.9b6b6fe.apk` supplied in the project conversation.

A DEX inventory pass found approximately:

- 2,805 `ani.saikou` classes
- 18,829 methods
- 22,125 relevant strings

High-density responsibility groups included:

- `ani.saikou.media`
- `ani.saikou.parsers.anime`
- `ani.saikou.parsers.anime.extractors`
- `ani.saikou.parsers.manga`
- `ani.saikou.connections.anilist`
- `ani.saikou.connections.anilist.api`
- `ani.saikou.media.anime`
- `ani.saikou.media.anime.mpv`
- `ani.saikou.media.manga.mangareader`
- `ani.saikou.home`
- `ani.saikou.settings`

Key architecture classes/method families used to derive KSP Ani's clean boundaries are documented in `docs/SAIKOU_ARCHITECTURE_MAP.md`.

Provider-specific unofficial video extractors were inventoried only to identify the separation between parser, server and extractor layers. Their implementation is not included in KSP Ani.
