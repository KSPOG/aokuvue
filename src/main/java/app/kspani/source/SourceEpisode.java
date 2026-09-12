package app.kspani.source;

import java.util.Map;

/** Episode state is provider-owned all the way from catalog to player. */
public record SourceEpisode(
        String providerId,
        String seriesId,
        String episodeId,
        String number,
        String title,
        String description,
        String thumbnailUrl,
        boolean filler,
        String group,
        Map<String, String> extra
) {
    public SourceEpisode {
        extra = extra == null ? Map.of() : Map.copyOf(extra);
    }

    public double numericNumber() {
        try {
            return Double.parseDouble(number.replaceAll("[^0-9.]", ""));
        } catch (Exception ignored) {
            return Double.NaN;
        }
    }
}
