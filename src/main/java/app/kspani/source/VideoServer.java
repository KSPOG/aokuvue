package app.kspani.source;

import java.util.Map;

/** A selectable playback server; not yet a media URL. */
public record VideoServer(
        String providerId,
        String serverId,
        String name,
        String reference,
        Map<String, String> extra
) {
    public VideoServer {
        extra = extra == null ? Map.of() : Map.copyOf(extra);
    }
}
