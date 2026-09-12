package app.kspani.source;

public record PlaybackResolution(
        AnimeSource source,
        SourceSeries series,
        SourceEpisode episode,
        ResolvedServer resolved,
        PlaybackSource selectedVideo,
        int selectedVideoIndex,
        Integer selectedSubtitleIndex
) {}
