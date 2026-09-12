package app.kspani.source;

import java.net.URI;
import java.util.Map;

public record PlaybackSource(
        URI uri,
        Integer quality,
        String container,
        String codec,
        String label,
        Map<String, String> headers
) {
    public PlaybackSource {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    public int qualityOrZero() {
        return quality == null ? 0 : quality;
    }
}
