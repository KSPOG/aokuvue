package app.kspani.source;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EverythingMoeHentaiDirectoryTest {
    @Test void parsesRankedAdultEntriesAndDirectLinks() {
        String html = """
                <h1>Hentai Streaming</h1>
                <a href="/s/hentai-tv" data-link="https://hentai.example/">hentai.tv</a>
                <a href="/s/example-mult" data-link="https://mult.example/">Example <b>MULT</b></a>
                """;
        var entries = new EverythingMoeHentaiDirectory().parseEntries(html);
        assertEquals(2, entries.size()); assertEquals("hentai.tv", entries.get(0).name());
        assertEquals("https://hentai.example/", entries.get(0).destination().toString()); assertTrue(entries.get(1).multiSource());
    }
    @Test void fallbackProvidesUsefulRankedAdultSources() {
        var sites = new EverythingMoeHentaiDirectory().fallbackSnapshot();
        assertTrue(sites.size() >= 20); assertEquals("hentai.tv", sites.get(0).name());
        assertEquals(1, sites.get(0).rank()); assertEquals(ProviderAccess.CATALOG_ONLY, sites.get(0).access());
    }
}
