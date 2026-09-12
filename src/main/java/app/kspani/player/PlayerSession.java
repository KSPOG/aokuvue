package app.kspani.player;

import app.kspani.domain.AniMedia;
import app.kspani.source.EpisodeLoadResult;
import app.kspani.source.PlaybackResolution;

public record PlayerSession(
        AniMedia media,
        EpisodeLoadResult episodeLoad,
        PlaybackResolution playback
) {}
