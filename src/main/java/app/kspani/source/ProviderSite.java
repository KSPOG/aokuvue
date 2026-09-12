package app.kspani.source;

import java.net.URI;

public record ProviderSite(
        String id,
        String name,
        URI baseUri,
        ProviderAccess access,
        boolean fromLiveDirectory,
        int rank,
        boolean multiSource
) {
    @Override
    public String toString() {
        return rank + ". " + name + (multiSource ? "  MULT" : "");
    }
}
