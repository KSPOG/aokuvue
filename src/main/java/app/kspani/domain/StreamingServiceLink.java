package app.kspani.domain;

import java.net.URI;

/** Official/legal streaming destination reported by AniList. */
public record StreamingServiceLink(
        String site,
        URI uri,
        String language,
        String notes,
        String iconUrl
) {}
