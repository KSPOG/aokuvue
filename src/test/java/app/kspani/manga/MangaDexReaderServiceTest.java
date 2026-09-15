package app.kspani.manga;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class MangaDexReaderServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void parsesTitleMatchesAndOriginalTitle() throws Exception {
        var root = mapper.readTree("""
                {"data":[{"id":"manga-1","attributes":{"title":{"en":"Blue Period"},
                "altTitles":[{"ja":"ブルーピリオド"}],"status":"ongoing"}}]}
                """);
        var matches = MangaDexReaderService.parseMatches(root);
        assertEquals(1, matches.size());
        assertEquals("Blue Period", matches.get(0).title());
        assertEquals("ブルーピリオド", matches.get(0).originalTitle());
    }

    @Test void excludesExternalAndEmptyChaptersAndKeepsGroupCredit() throws Exception {
        var root = mapper.readTree("""
                {"data":[
                  {"id":"chapter-1","attributes":{"volume":"1","chapter":"2","title":"Canvas","translatedLanguage":"en","pages":25,"externalUrl":null,"publishAt":"2026-01-01"},
                   "relationships":[{"type":"scanlation_group","attributes":{"name":"Example Group"}}]},
                  {"id":"external","attributes":{"pages":20,"externalUrl":"https://example.test"}},
                  {"id":"empty","attributes":{"pages":0,"externalUrl":null}}
                ]}
                """);
        var chapters = MangaDexReaderService.parseChapters(root);
        assertEquals(1, chapters.size());
        assertEquals("Example Group", chapters.get(0).group());
        assertEquals(25, chapters.get(0).pages());
    }

    @Test void buildsAtHomeImageUrlsAndRejectsEmptyResponses() throws Exception {
        var root = mapper.readTree("""
                {"baseUrl":"https://uploads.example.test","chapter":{"hash":"abc","data":["1.jpg","2.jpg"]}}
                """);
        var pages = MangaDexReaderService.parsePages("chapter-1", root);
        assertEquals("https://uploads.example.test/data/abc/1.jpg", pages.pages().get(0).toString());
        assertEquals(2, pages.pages().size());
        assertThrows(IllegalStateException.class,
                () -> MangaDexReaderService.parsePages("empty", mapper.createObjectNode()));
    }
}
