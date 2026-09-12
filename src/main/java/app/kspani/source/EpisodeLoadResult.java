package app.kspani.source;

import java.util.List;

public record EpisodeLoadResult(
        AnimeSource source,
        SourceSeries series,
        List<SourceEpisode> episodes,
        boolean restoredMatch,
        List<SourceSeries> alternativeMatches
) {
    public EpisodeLoadResult {
        episodes = episodes == null ? List.of() : List.copyOf(episodes);
        alternativeMatches = alternativeMatches == null ? List.of() : List.copyOf(alternativeMatches);
    }
}
