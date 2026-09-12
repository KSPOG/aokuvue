package app.kspani.source;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EverythingMoeProviderDirectoryTest {
    @Test
    void parsesOnlyRankedAnimeStreamingEntriesInOrder() {
        String html = """
                <h2>Anime <span>Streaming</span></h2>
                <a href="/s/anikoto">Anikoto</a>
                <a href="/s/animepahe"><strong>animepahe</strong></a>
                <h2>Donghua <span>Streaming</span></h2>
                <a href="/s/animekhor">AnimeKhor</a>
                """;
        EverythingMoeProviderDirectory directory = new EverythingMoeProviderDirectory(new SourceRegistry());

        var entries = directory.parseEntries(html);

        assertEquals(2, entries.size());
        assertEquals("Anikoto", entries.get(0).name());
        assertEquals("animepahe", entries.get(1).name());
    }

    @Test
    void automaticallyRecommendsHighestRankedDirectorySource() {
        EverythingMoeProviderDirectory directory = new EverythingMoeProviderDirectory(new SourceRegistry());

        assertTrue(directory.recommendedSite().isPresent());
        assertEquals("Anikoto", directory.recommendedSite().orElseThrow().name());
        assertEquals(1, directory.recommendedSite().orElseThrow().rank());
        assertEquals("https://anikototv.to/home", directory.recommendedSite().orElseThrow().baseUri().toString());
        assertEquals(93, directory.fallbackSnapshot().size());
        assertEquals(93, directory.fallbackSnapshot().get(92).rank());
        assertEquals("AnimeDekho", directory.fallbackSnapshot().get(92).name());
        assertTrue(directory.fallbackSnapshot().stream()
                .filter(site -> site.name().equals("Miruro"))
                .findFirst().orElseThrow().multiSource());
    }

    @Test
    void parsesEverythingMoeLowRankJson() {
        EverythingMoeProviderDirectory directory = new EverythingMoeProviderDirectory(new SourceRegistry());
        var entries = directory.parseJsonEntries("""
                [{"title":"Anikage","link":"https://anikage.cc/home","id":"anicore","tags":"mult"}]
                """);

        assertEquals(1, entries.size());
        assertEquals("Anikage", entries.get(0).name());
        assertEquals("https://anikage.cc/home", entries.get(0).destination().toString());
        assertTrue(entries.get(0).multiSource());
    }
}
