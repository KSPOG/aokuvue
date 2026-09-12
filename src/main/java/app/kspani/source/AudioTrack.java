package app.kspani.source;

import java.net.URI;
import java.util.Map;

public record AudioTrack(String language, URI uri, Map<String, String> headers) {
    public AudioTrack {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }
}
