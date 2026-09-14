package app.kspani.source;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EverythingMoeMangaDirectoryTest {
    @Test
    void parsesRankedMangaEntriesAndDirectLinks() {
        String html = """
                <h1>Manga Reading</h1>
                <a href="/s/comix" data-link="https://comix.example/">Comix <span>HUB</span></a>
                <a href="/s/cubari-proxy" data-link="https://cubari.example/">Cubari Proxy <b>MULT</b></a>
                """;
        EverythingMoeMangaDirectory directory = new EverythingMoeMangaDirectory();

        var entries = directory.parseEntries(html);

        assertEquals(2, entries.size());
        assertEquals("Comix HUB", entries.get(0).name());
        assertEquals("https://comix.example/", entries.get(0).destination().toString());
        assertTrue(entries.get(1).multiSource());
    }

    @Test
    void fallbackProvidesUsefulMangaSources() {
        EverythingMoeMangaDirectory directory = new EverythingMoeMangaDirectory();
        var sites = directory.fallbackSnapshot();

        assertTrue(sites.size() >= 20);
        assertEquals("Comix", sites.get(0).name());
        assertEquals(1, sites.get(0).rank());
        assertEquals(ProviderAccess.CATALOG_ONLY, sites.get(0).access());
    }
}
