package app.kspani.player;

import java.util.Locale;

/** Classifies provider-player pages that loaded successfully but report a missing media file. */
public final class EmbeddedPlayerStatus {
    private EmbeddedPlayerStatus() {}

    public static boolean unavailable(String pageText) {
        if (pageText == null || pageText.isBlank()) return false;
        String normalized = pageText.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        return normalized.contains("error code: 410")
                || normalized.contains("error code 410")
                || normalized.contains("error code: 404")
                || normalized.contains("error code 404")
                || (normalized.contains("can't find the file")
                    && normalized.contains("deleted"));
    }
}
