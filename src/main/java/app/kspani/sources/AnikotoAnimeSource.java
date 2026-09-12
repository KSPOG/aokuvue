package app.kspani.sources;

import app.kspani.app.JsonHttpClient;
import app.kspani.domain.AniMedia;
import app.kspani.source.AnimeSource;
import app.kspani.source.PlaybackSource;
import app.kspani.source.ResolvedServer;
import app.kspani.source.SourceCapabilities;
import app.kspani.source.SourceDescriptor;
import app.kspani.source.SourceEpisode;
import app.kspani.source.SourceSeries;
import app.kspani.source.SubtitleTrack;
import app.kspani.source.VideoServer;
import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Episode adapter for Anikoto, the highest-ranked EverythingMoe source at the time this adapter
 * was added. Provider HTML and AJAX payloads stay behind the AnimeSource boundary so a provider
 * change fails cleanly without corrupting saved AniList or playback state.
 */
public final class AnikotoAnimeSource implements AnimeSource {
    private static final URI BASE = URI.create("https://anikototv.to/");
    private static final Map<String, String> AJAX_HEADERS = Map.of(
            "X-Requested-With", "XMLHttpRequest",
            "Referer", BASE.toString()
    );
    private static final Pattern SEARCH_ITEM = Pattern.compile(
            "<div class=\"ani poster[^>]*>.*?<img[^>]+src=\"([^\"]*)\"[^>]+alt=\"([^\"]*)\".*?"
                    + "<a class=\"name d-title\" href=\"([^\"]+)\"[^>]*>(.*?)</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern MEDIA_ID = Pattern.compile(
            "id=\"watch-main\"[^>]*data-id=\"(\\d+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern EPISODE = Pattern.compile("<a\\s+([^>]*data-id=\"[^\"]+\"[^>]*)>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SERVER_GROUP = Pattern.compile(
            "<div class=\"type\" data-type=\"([^\"]*)\">(.*?)(?=<div class=\"type\"|</div></div>\\s*$)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern SERVER = Pattern.compile(
            "<li\\s+([^>]*data-link-id=\"[^\"]+\"[^>]*)>(.*?)</li>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ATTRIBUTE = Pattern.compile("([a-zA-Z0-9_-]+)=\"([^\"]*)\"");
    private static final Pattern PLAYER_ID = Pattern.compile(
            "id=\"megaplay-player\"[^>]*data-id=\"(\\d+)\"",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final JsonHttpClient http;
    private final SourceDescriptor descriptor = new SourceDescriptor(
            "anikoto", "Anikoto · EverythingMoe #1", "English",
            new SourceCapabilities(true, true, false, false, true, false), 1);

    public AnikotoAnimeSource(JsonHttpClient http) {
        this.http = http;
    }

    @Override public SourceDescriptor descriptor() { return descriptor; }
    @Override public boolean isConfigured() { return true; }
    @Override public List<String> providerDomains() { return List.of("anikototv.to"); }

    @Override
    public CompletableFuture<List<SourceSeries>> search(AniMedia media) {
        String query = media.searchTitles().stream().findFirst().orElse(media.title());
        URI uri = BASE.resolve("filter?keyword=" + URLEncoder.encode(query, StandardCharsets.UTF_8));
        return http.getHtml(uri, Map.of()).thenApply(AnikotoAnimeSource::parseSearchResults);
    }

    @Override
    public CompletableFuture<List<SourceEpisode>> loadEpisodes(AniMedia media, SourceSeries series) {
        URI watch = URI.create(series.link());
        return http.getHtml(watch, Map.of("Referer", BASE.toString())).thenCompose(html -> {
            Matcher id = MEDIA_ID.matcher(html);
            if (!id.find()) return CompletableFuture.failedFuture(
                    new IllegalStateException("Anikoto no longer exposes a media identifier for this title."));
            URI endpoint = BASE.resolve("ajax/episode/list/" + id.group(1));
            return http.get(endpoint, headers(watch)).thenApply(json -> {
                if (json.path("status").asInt() != 200) {
                    throw new IllegalStateException(json.path("message").asText("Anikoto episode request failed."));
                }
                return parseEpisodes(series, json.path("result").asText());
            });
        });
    }

    @Override
    public CompletableFuture<List<VideoServer>> loadVideoServers(
            AniMedia media, SourceSeries series, SourceEpisode episode) {
        String ids = episode.extra().getOrDefault("serverIds", "");
        if (ids.isBlank()) return CompletableFuture.failedFuture(
                new IllegalStateException("Anikoto returned no servers for Episode " + episode.number() + "."));
        URI endpoint = BASE.resolve("ajax/server/list?servers="
                + URLEncoder.encode(ids, StandardCharsets.UTF_8));
        return http.get(endpoint, headers(URI.create(series.link()))).thenApply(json -> {
            if (json.path("status").asInt() != 200) {
                throw new IllegalStateException(json.path("message").asText("Anikoto server request failed."));
            }
            return parseServers(json.path("result").asText());
        });
    }

    @Override
    public CompletableFuture<ResolvedServer> resolveServer(
            AniMedia media, SourceSeries series, SourceEpisode episode, VideoServer server) {
        URI endpoint = BASE.resolve("ajax/server?get="
                + URLEncoder.encode(server.reference(), StandardCharsets.UTF_8));
        return http.get(endpoint, headers(URI.create(series.link()))).thenCompose(json -> {
            if (json.path("status").asInt() != 200) {
                throw new IllegalStateException(json.path("message").asText("Anikoto player request failed."));
            }
            String raw = json.path("result").path("url").asText("");
            if (raw.isBlank()) throw new IllegalStateException("Anikoto returned an empty player URL.");
            URI embed = URI.create(raw);
            return resolveMegaPlay(embed, server, series);
        });
    }

    private CompletableFuture<ResolvedServer> resolveMegaPlay(
            URI embed, VideoServer server, SourceSeries series) {
        Map<String, String> playerHeaders = Map.of("Referer", series.link());
        return http.getHtml(embed, playerHeaders).thenCompose(html -> {
            Matcher playerId = PLAYER_ID.matcher(html);
            if (!playerId.find()) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "The Anikoto video host no longer exposes its player identifier."));
            }

            StringBuilder query = new StringBuilder("stream/getSourcesNew?id=")
                    .append(URLEncoder.encode(playerId.group(1), StandardCharsets.UTF_8));
            String cdn = queryParameter(embed, "s");
            if (!cdn.isBlank()) query.append("&s=")
                    .append(URLEncoder.encode(cdn, StandardCharsets.UTF_8));
            URI sourceEndpoint = embed.resolve("/" + query);
            Map<String, String> sourceHeaders = Map.of(
                    "Referer", embed.toString(),
                    "X-Requested-With", "XMLHttpRequest");
            return http.get(sourceEndpoint, sourceHeaders)
                    .thenApply(json -> parsePlayerSources(server, embed, json));
        });
    }

    static ResolvedServer parsePlayerSources(VideoServer server, URI embed, JsonNode json) {
        String rawVideo = json.path("sources").path("file").asText("");
        if (rawVideo.isBlank() && json.path("sources").isArray()
                && !json.path("sources").isEmpty()) {
            rawVideo = json.path("sources").path(0).path("file").asText("");
        }
        if (rawVideo.isBlank()) {
            throw new IllegalStateException("The Anikoto video host returned no playable stream.");
        }

        // MegaPlay's CDN accepts its origin as Referer but rejects the full /stream/... URL.
        // Keep this header on the native stream so the loopback relay can load every HLS asset.
        String playerOrigin = embed.getScheme() + "://" + embed.getAuthority() + "/";
        Map<String, String> mediaHeaders = Map.of("Referer", playerOrigin);
        PlaybackSource video = new PlaybackSource(
                URI.create(rawVideo), null, "hls", null, server.name(), mediaHeaders);
        List<SubtitleTrack> subtitles = new ArrayList<>();
        for (JsonNode track : json.path("tracks")) {
            String kind = track.path("kind").asText("");
            String file = track.path("file").asText("");
            if (file.isBlank() || (!kind.isBlank() && !"captions".equalsIgnoreCase(kind)
                    && !"subtitles".equalsIgnoreCase(kind))) continue;
            subtitles.add(new SubtitleTrack(
                    track.path("label").asText("Subtitle"), URI.create(file),
                    track.path("default").asBoolean(false), mediaHeaders));
        }
        return new ResolvedServer(server, List.of(video), subtitles, List.of());
    }

    static List<SourceSeries> parseSearchResults(String html) {
        List<SourceSeries> result = new ArrayList<>();
        Matcher matcher = SEARCH_ITEM.matcher(html == null ? "" : html);
        while (matcher.find()) {
            String title = text(matcher.group(4));
            String link = matcher.group(3).trim();
            String id = link.replaceFirst("(?i)^https?://[^/]+/watch/", "")
                    .replaceFirst("/ep-[^/?#]+.*$", "");
            result.add(new SourceSeries("anikoto", id, title, link, matcher.group(1),
                    List.of(text(matcher.group(2))), null, Map.of()));
        }
        return List.copyOf(result);
    }

    static List<SourceEpisode> parseEpisodes(SourceSeries series, String html) {
        List<SourceEpisode> result = new ArrayList<>();
        Matcher matcher = EPISODE.matcher(html == null ? "" : html);
        while (matcher.find()) {
            Map<String, String> attributes = attributes(matcher.group(1));
            String number = attributes.getOrDefault("data-num", attributes.getOrDefault("data-slug", ""));
            if (number.isBlank()) continue;
            Map<String, String> extra = new LinkedHashMap<>();
            extra.put("serverIds", attributes.getOrDefault("data-ids", ""));
            extra.put("sub", attributes.getOrDefault("data-sub", "0"));
            extra.put("dub", attributes.getOrDefault("data-dub", "0"));
            result.add(new SourceEpisode("anikoto", series.seriesId(),
                    attributes.getOrDefault("data-id", number), number, "Episode " + number,
                    "", "", false, languageGroup(extra), extra));
        }
        return List.copyOf(result);
    }

    static List<VideoServer> parseServers(String html) {
        List<VideoServer> result = new ArrayList<>();
        Matcher groupMatcher = SERVER_GROUP.matcher(html == null ? "" : html);
        while (groupMatcher.find()) {
            String language = groupMatcher.group(1);
            Matcher serverMatcher = SERVER.matcher(groupMatcher.group(2));
            while (serverMatcher.find()) {
                Map<String, String> attributes = attributes(serverMatcher.group(1));
                String reference = attributes.getOrDefault("data-link-id", "");
                if (reference.isBlank()) continue;
                String name = text(serverMatcher.group(2));
                result.add(new VideoServer("anikoto",
                        attributes.getOrDefault("data-sv-id", reference),
                        name + (language.isBlank() ? "" : " · " + language.toUpperCase()), reference,
                        Map.of("language", language)));
            }
        }
        return List.copyOf(result);
    }

    private static Map<String, String> headers(URI referer) {
        Map<String, String> result = new LinkedHashMap<>(AJAX_HEADERS);
        result.put("Referer", referer.toString());
        return result;
    }

    private static String queryParameter(URI uri, String name) {
        if (uri == null || uri.getRawQuery() == null || name == null) return "";
        for (String pair : uri.getRawQuery().split("&")) {
            int separator = pair.indexOf('=');
            String key = separator < 0 ? pair : pair.substring(0, separator);
            if (name.equalsIgnoreCase(key)) {
                return separator < 0 ? "" : java.net.URLDecoder.decode(
                        pair.substring(separator + 1), StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    private static Map<String, String> attributes(String raw) {
        Map<String, String> result = new LinkedHashMap<>();
        Matcher matcher = ATTRIBUTE.matcher(raw == null ? "" : raw);
        while (matcher.find()) result.put(matcher.group(1).toLowerCase(), htmlDecode(matcher.group(2)));
        return result;
    }

    private static String languageGroup(Map<String, String> extra) {
        boolean sub = "1".equals(extra.get("sub"));
        boolean dub = "1".equals(extra.get("dub"));
        return sub && dub ? "SUB · DUB" : dub ? "DUB" : sub ? "SUB" : "";
    }

    private static String text(String html) {
        return htmlDecode((html == null ? "" : html).replaceAll("<[^>]+>", " ")
                .replaceAll("\\s+", " ").trim());
    }

    private static String htmlDecode(String value) {
        return value.replace("&amp;amp;", "&").replace("&amp;", "&")
                .replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&lt;", "<").replace("&gt;", ">");
    }
}
