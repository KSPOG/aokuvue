package app.kspani.sources;

import app.kspani.source.SourceSeries;
import app.kspani.source.VideoServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        VideoServer server = new VideoServer(
                "anikoto", "one", "Vidstream-2 · SUB", "token", Map.of());

        var resolved = AnikotoAnimeSource.parsePlayerSources(
                server, URI.create("https://megaplay.buzz/stream/s-2/1/sub"), json);

        assertEquals(1, resolved.videos().size());
        assertEquals("hls", resolved.videos().get(0).container());
        assertEquals(
                URI.create("https://cdn.example/anime/master.m3u8"),
                resolved.videos().get(0).uri());
        assertEquals(
                "https://megaplay.buzz/",
                resolved.videos().get(0).headers().get("Referer"));
        assertEquals(1, resolved.subtitles().size());
        assertEquals("English", resolved.subtitles().get(0).language());
        assertTrue(resolved.subtitles().get(0).defaultTrack());
    }

    @Test
    void acceptsCurrentAndDefensiveMegaPlaySourceShapes() throws Exception {
        var json = new ObjectMapper().readTree("""
                {
                  "sources": [
                    "https://megap.kotocdn.site/a/master.m3u8",
                    {"url": "https://media.example/video.mp4", "quality": "1080p"}
                  ],
                  "data": {
                    "links": {"src": "https://media.example/backup/master.m3u8", "label": "720p"},
                    "captions": [
                      {"url": "https://media.example/subs/en.vtt", "title": "English", "type": "subtitle"}
                    ]
                  }
                }
                """);
        VideoServer server = new VideoServer(
                "anikoto", "one", "MegaPlay · SUB", "token", Map.of());

        var resolved = AnikotoAnimeSource.parsePlayerSources(
                server, URI.create("https://megaplay.buzz/stream/s-2/1/sub"), json);

        assertEquals(3, resolved.videos().size());
        assertEquals("hls", resolved.videos().get(0).container());
        assertEquals("mp4", resolved.videos().get(1).container());
        assertEquals(Integer.valueOf(1080), resolved.videos().get(1).quality());
        assertEquals(Integer.valueOf(720), resolved.videos().get(2).quality());
        assertEquals("https://megaplay.buzz", resolved.videos().get(0).headers().get("Origin"));
        assertEquals(1, resolved.subtitles().size());
        assertEquals("English", resolved.subtitles().get(0).language());
    }

    @Test
    void megaPlayMediaHostMatchingRejectsLookalikes() {
        assertTrue(AnikotoAnimeSource.isMegaPlayMediaHost("megap.kotocdn.site"));
        assertTrue(AnikotoAnimeSource.isMegaPlayMediaHost("cdn.voltara.click"));
        assertFalse(AnikotoAnimeSource.isMegaPlayMediaHost("evilkotocdn.site.example"));
        assertFalse(AnikotoAnimeSource.isMegaPlayMediaHost("notmegaplay.buzz.example"));
    }
}
