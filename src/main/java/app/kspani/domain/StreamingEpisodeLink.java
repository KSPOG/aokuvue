package app.kspani.domain;

import java.net.URI;

/** Official/legal episode link reported by AniList's streamingEpisodes field. */
public record StreamingEpisodeLink(
        String title,
        String site,
        URI uri,
        String thumbnailUrl
) {}
