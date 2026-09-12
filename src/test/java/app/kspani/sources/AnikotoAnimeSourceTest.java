package app.kspani.sources;

import app.kspani.source.SourceSeries;
import app.kspani.source.VideoServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class AnikotoAnimeSourceTest {
    @Test
    void parsesSearchEpisodesAndLanguageServers() {
        String search = """
                <div class="ani poster tip"><a><img src="cover.jpg" alt="Naruto" /></a></div>
                <a class="name d-title" href="https://anikototv.to/watch/naruto-eybxz/ep-1">Naruto</a>
                """;
        var matches = AnikotoAnimeSource.parseSearchResults(search);
        assertEquals(1, matches.size());
        assertEquals("naruto-eybxz", matches.get(0).seriesId());

        SourceSeries series = matches.get(0);
        String episodes = """
                <a href="#" data-id="16638" data-num="1" data-slug="1" data-sub="1" data-dub="1" data-ids="server-token"></a>
                <a href="#" data-id="16639" data-num="2" data-slug="2" data-sub="1" data-dub="0" data-ids="server-token-2"></a>
                """;
        var parsedEpisodes = AnikotoAnimeSource.parseEpisodes(series, episodes);
        assertEquals(2, parsedEpisodes.size());
        assertEquals("SUB · DUB", parsedEpisodes.get(0).group());
        assertEquals("server-token-2", parsedEpisodes.get(1).extra().get("serverIds"));

        String servers = """
                <div class="servers"><div class="type" data-type="sub"><label>SUB</label><ul>
                <li data-ep-id="16638" data-sv-id="e54" data-link-id="sub-link">Vidstream-2</li></ul>
                </div><div class="type" data-type="dub"><label>DUB</label><ul>
                <li data-ep-id="16638" data-sv-id="323" data-link-id="dub-link">HD-1</li></ul>
                </div></div>
                """;
        var parsedServers = AnikotoAnimeSource.parseServers(servers);
        assertEquals(2, parsedServers.size());
        assertEquals("Vidstream-2 · SUB", parsedServers.get(0).name());
        assertEquals("dub-link", parsedServers.get(1).reference());
        assertEquals(Map.of("language", "dub"), parsedServers.get(1).extra());
    }

    @Test
    void convertsPlayerApiResponseToNativeHlsAndSubtitleTracks() throws Exception {
        var json = new ObjectMapper().readTree("""
                {
                  "sources": {"file": "https://cdn.example/anime/master.m3u8"},
                  "tracks": [
                    {"file": "https://cdn.example/subs/english.vtt", "label": "English", "kind": "captions", "default": true},
                    {"file": "https://cdn.example/thumbs.vtt", "label": "Thumbnails", "kind": "thumbnails"}
                  ]
                }
                """);
        VideoServer server = new VideoServer("anikoto", "one", "Vidstream-2 · SUB", "token", Map.of());

        var resolved = AnikotoAnimeSource.parsePlayerSources(
                server, URI.create("https://megaplay.example/embed/1"), json);

        assertEquals("hls", resolved.videos().get(0).container());
        assertEquals(URI.create("https://cdn.example/anime/master.m3u8"), resolved.videos().get(0).uri());
        assertEquals("https://megaplay.example/", resolved.videos().get(0).headers().get("Referer"));
        assertEquals(1, resolved.subtitles().size());
        assertEquals("English", resolved.subtitles().get(0).language());
        assertEquals(true, resolved.subtitles().get(0).defaultTrack());
    }
}
