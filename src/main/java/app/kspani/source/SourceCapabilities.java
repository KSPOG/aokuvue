package app.kspani.source;

public record SourceCapabilities(
        boolean searchable,
        boolean directPlayback,
        boolean subtitles,
        boolean audioTracks,
        boolean dubSelection,
        boolean episodeThumbnails
) {
    public static SourceCapabilities trackingOnly() {
        return new SourceCapabilities(false, false, false, false, false, false);
    }

    public static SourceCapabilities direct(boolean searchable) {
        return new SourceCapabilities(searchable, true, true, true, false, true);
    }
}
