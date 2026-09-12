package app.kspani.source;

import java.util.List;

/** Equivalent responsibility to Saikou's resolved VideoExtractor payload. */
public record ResolvedServer(
        VideoServer server,
        List<PlaybackSource> videos,
        List<SubtitleTrack> subtitles,
        List<AudioTrack> audioTracks
) {
    public ResolvedServer {
        videos = videos == null ? List.of() : List.copyOf(videos);
        subtitles = subtitles == null ? List.of() : List.copyOf(subtitles);
        audioTracks = audioTracks == null ? List.of() : List.copyOf(audioTracks);
    }

    public boolean playable() {
        return !videos.isEmpty();
    }
}
