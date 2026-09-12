package app.kspani.app;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AppContextSourceRegistryTest {
    @Test
    void registersHighestRankedEverythingMoePlaybackAdapter() {
        var registry = AppContext.createSourceRegistry(new JsonHttpClient());

        assertEquals(1, registry.playbackSources().size());
        assertEquals("anikoto", registry.playbackSources().get(0).descriptor().id());
        assertTrue(registry.playbackSources().get(0).providerDomains().contains("anikototv.to"));
    }
}
