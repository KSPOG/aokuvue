package app.kspani.source;

/**
 * How Aokuvue can currently use a discovered provider.
 *
 * DIRECT_PLUGIN means an AnimeSource implementation can resolve media for the native player.
 * CATALOG_ONLY means the site is known/discoverable but no direct media resolver is bundled.
 * OFFICIAL_EXTERNAL is reserved for licensed services surfaced from AniList metadata.
 */
public enum ProviderAccess {
    DIRECT_PLUGIN,
    CATALOG_ONLY,
    OFFICIAL_EXTERNAL
}
