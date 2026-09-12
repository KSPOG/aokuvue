package app.kspani.source;

import java.net.URI;
import java.util.Map;

public record SubtitleTrack(
        String language,
        URI uri,
        boolean defaultTrack,
        Map<String, String> headers
) {
    public SubtitleTrack {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }
}
