package app.kspani.source;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Provider-independent normalization only. It never changes provider identity. */
public final class EpisodeNormalizer {
    private EpisodeNormalizer() {}

    public static List<SourceEpisode> normalize(List<SourceEpisode> episodes) {
        if (episodes == null) return List.of();
        Map<String, SourceEpisode> unique = new LinkedHashMap<>();
        for (SourceEpisode episode : episodes) {
            if (episode == null || episode.number() == null || episode.number().isBlank()) continue;
            String group = episode.group() == null ? "" : episode.group().trim().toLowerCase();
            unique.putIfAbsent(episode.number().trim() + "|" + group, episode);
        }
        List<SourceEpisode> normalized = new ArrayList<>(unique.values());
        normalized.sort((a, b) -> {
            double x = a.numericNumber();
            double y = b.numericNumber();
            if (!Double.isNaN(x) && !Double.isNaN(y)) return Double.compare(x, y);
            return a.number().compareToIgnoreCase(b.number());
        });
        return List.copyOf(normalized);
    }
}
