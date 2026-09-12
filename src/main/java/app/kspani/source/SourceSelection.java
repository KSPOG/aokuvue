package app.kspani.source;

/** Per-media persistent selection, mirroring Saikou's Selected responsibility. */
public record SourceSelection(
        int mediaId,
        String sourceId,
        String serverName,
        boolean preferDub,
        int videoIndex,
        Integer subtitleIndex,
        String selectedEpisode,
        PlaybackLanguage languagePreference
) {
    public SourceSelection {
        languagePreference = languagePreference == null
                ? (preferDub ? PlaybackLanguage.ENGLISH_DUB : PlaybackLanguage.AUTO)
                : languagePreference;
        preferDub = languagePreference.prefersDub();
    }

    public static SourceSelection defaults(int mediaId, String sourceId, PlaybackLanguage languagePreference) {
        PlaybackLanguage language = languagePreference == null ? PlaybackLanguage.AUTO : languagePreference;
        return new SourceSelection(mediaId, sourceId, "", language.prefersDub(), 0, null, "", language);
    }

    /** Backward-compatible constructor used by older source plug-ins/tests. */
    public static SourceSelection defaults(int mediaId, String sourceId, boolean preferDub) {
        return defaults(mediaId, sourceId, preferDub ? PlaybackLanguage.ENGLISH_DUB : PlaybackLanguage.AUTO);
    }

    public SourceSelection withSource(String newSource) {
        return new SourceSelection(mediaId, newSource, "", preferDub, 0, null, selectedEpisode, languagePreference);
    }

    public SourceSelection withServer(String server) {
        return new SourceSelection(mediaId, sourceId, server == null ? "" : server, preferDub, videoIndex, subtitleIndex, selectedEpisode, languagePreference);
    }

    public SourceSelection withPreferDub(boolean value) {
        return withLanguage(value ? PlaybackLanguage.ENGLISH_DUB : PlaybackLanguage.ENGLISH_SUB);
    }

    public SourceSelection withLanguage(PlaybackLanguage language) {
        PlaybackLanguage next = language == null ? PlaybackLanguage.AUTO : language;
        // Server/video/subtitle choices may belong to a different language variant.
        return new SourceSelection(mediaId, sourceId, "", next.prefersDub(), 0, null, selectedEpisode, next);
    }

    public SourceSelection withVideoIndex(int index) {
        return new SourceSelection(mediaId, sourceId, serverName, preferDub, Math.max(0, index), subtitleIndex, selectedEpisode, languagePreference);
    }

    public SourceSelection withSubtitleIndex(Integer index) {
        return new SourceSelection(mediaId, sourceId, serverName, preferDub, videoIndex, index, selectedEpisode, languagePreference);
    }

    public SourceSelection withEpisode(String episode) {
        return new SourceSelection(mediaId, sourceId, serverName, preferDub, videoIndex, subtitleIndex, episode == null ? "" : episode, languagePreference);
    }
}
