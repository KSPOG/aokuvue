package app.kspani.source;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Language-aware selection that never fabricates or renumbers provider episodes. */
public final class EpisodeLanguageSelector {
    private EpisodeLanguageSelector() {}

    public static List<SourceEpisode> filterEpisodes(List<SourceEpisode> episodes, PlaybackLanguage preference) {
        if (episodes == null || episodes.isEmpty()) return List.of();
        PlaybackLanguage pref = preference == null ? PlaybackLanguage.AUTO : preference;
        if (pref == PlaybackLanguage.AUTO) return List.copyOf(episodes);

        List<SourceEpisode> preferred = new ArrayList<>();
        for (SourceEpisode episode : episodes) {
            String text = languageText(episode.group(), episode.title(), episode.extra().get("language"), episode.extra().get("audio"));
            // Dual-language episode entries must remain visible for either preference. The actual
            // Sub/Dub choice is applied when ranking that episode's servers.
            if (pref == PlaybackLanguage.ENGLISH_DUB && isDub(text)) preferred.add(episode);
            if (pref == PlaybackLanguage.ENGLISH_SUB && isSub(text)) preferred.add(episode);
        }

        // A source may not annotate its language at episode level. Keep its real episode list
        // and apply the preference later to server/video/subtitle resolution instead of hiding it.
        return preferred.isEmpty() ? List.copyOf(episodes) : List.copyOf(preferred);
    }

    public static List<VideoServer> rankServers(List<VideoServer> servers, PlaybackLanguage preference, String savedName) {
        if (servers == null || servers.isEmpty()) return List.of();
        PlaybackLanguage pref = preference == null ? PlaybackLanguage.AUTO : preference;
        List<VideoServer> ranked = new ArrayList<>(servers);
        ranked.sort(Comparator
                .comparingInt((VideoServer s) -> serverScore(s, pref, savedName)).reversed()
                .thenComparing(VideoServer::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(ranked);
    }

    public static List<PlaybackSource> rankVideos(List<PlaybackSource> videos, PlaybackLanguage preference) {
        if (videos == null || videos.isEmpty()) return List.of();
        PlaybackLanguage pref = preference == null ? PlaybackLanguage.AUTO : preference;
        List<PlaybackSource> ranked = new ArrayList<>(videos);
        ranked.sort(Comparator
                .comparingInt((PlaybackSource v) -> videoLanguageScore(v, pref)).reversed()
                .thenComparing(Comparator.comparingInt(PlaybackSource::qualityOrZero).reversed()));
        return List.copyOf(ranked);
    }

    public static Integer englishSubtitleIndex(List<SubtitleTrack> subtitles, Integer savedIndex) {
        if (subtitles == null || subtitles.isEmpty()) return null;
        if (savedIndex != null && savedIndex >= 0 && savedIndex < subtitles.size()
                && isEnglish(subtitles.get(savedIndex).language())) return savedIndex;
        for (int i = 0; i < subtitles.size(); i++) {
            SubtitleTrack track = subtitles.get(i);
            if (isEnglish(track.language()) && track.defaultTrack()) return i;
        }
        for (int i = 0; i < subtitles.size(); i++) {
            if (isEnglish(subtitles.get(i).language())) return i;
        }
        return null;
    }

    /** Selects the provider-designated caption track when the user has not made a choice yet. */
    public static Integer defaultSubtitleIndex(List<SubtitleTrack> subtitles, Integer savedIndex) {
        if (subtitles == null || subtitles.isEmpty()) return null;
        if (savedIndex != null && savedIndex >= 0 && savedIndex < subtitles.size()) return savedIndex;
        for (int i = 0; i < subtitles.size(); i++) {
            if (subtitles.get(i).defaultTrack()) return i;
        }
        return null;
    }

    private static int serverScore(VideoServer server, PlaybackLanguage pref, String savedName) {
        int score = 0;
        if (savedName != null && !savedName.isBlank() && server.name().equalsIgnoreCase(savedName)) score += 100;
        if ("true".equalsIgnoreCase(server.extra().get("ephemeralCandidate"))) {
            try {
                int candidateIndex = Integer.parseInt(server.extra().getOrDefault("candidateIndex", "1"));
                score -= Math.max(0, candidateIndex - 1) * 200;
            } catch (NumberFormatException ignored) {
                // Keep normal ranking if provider metadata is malformed.
            }
        }
        String text = languageText(server.name(), server.reference(), server.extra().get("language"), server.extra().get("audio"));
        if (pref == PlaybackLanguage.ENGLISH_DUB) {
            if (isDub(text)) score += 80;
            if (isSub(text) && !isDub(text)) score -= 30;
        } else if (pref == PlaybackLanguage.ENGLISH_SUB) {
            if (isSub(text) && !isDub(text)) score += 80;
            if (isDub(text)) score -= 30;
        }
        return score;
    }

    private static int videoLanguageScore(PlaybackSource video, PlaybackLanguage pref) {
        String text = languageText(video.label(), video.codec(), video.container(), "");
        if (pref == PlaybackLanguage.ENGLISH_DUB) return isDub(text) ? 30 : 0;
        if (pref == PlaybackLanguage.ENGLISH_SUB) return isSub(text) && !isDub(text) ? 30 : 0;
        return 0;
    }

    private static String languageText(String... values) {
        StringBuilder b = new StringBuilder();
        for (String value : values) if (value != null) b.append(' ').append(value.toLowerCase(Locale.ROOT));
        return b.toString();
    }

    private static boolean isDub(String text) {
        return text.contains(" dub") || text.contains("dubbed") || text.contains("english audio") || text.contains("eng dub");
    }

    private static boolean isSub(String text) {
        return text.contains(" sub") || text.contains("subbed") || text.contains("subtitle") || text.contains("eng sub") || text.contains("english sub");
    }

    private static boolean isEnglish(String language) {
        if (language == null) return false;
        String l = language.trim().toLowerCase(Locale.ROOT);
        return l.equals("en") || l.equals("eng") || l.startsWith("english") || l.startsWith("en-");
    }
}
