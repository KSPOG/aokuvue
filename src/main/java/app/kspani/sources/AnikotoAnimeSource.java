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
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Episode adapter for Anikoto.
 *
 * Anikoto owns catalog/episode lookup while MegaPlay resolves the selected provider embed into
 * native media. Keep those two layers separate: Anikoto IDs are not MegaPlay player IDs and the
 * MegaPlay source endpoint is provider-specific and can change independently.
 */
public final class AnikotoAnimeSource implements AnimeSource {
    private static final URI BASE = URI.create("https://anikototv.to/");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final byte[] MEGAPLAY_KEY = paddedKey("i?LMTAx0Q6,:}50U", 32);
    private static final byte[] MEGAPLAY_IV = "W0;27ToaUpl_P%'c".getBytes(StandardCharsets.UTF_8);
    private static final String BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36";
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
    private static final Pattern EPISODE = Pattern.compile(
            "<a\\s+([^>]*data-id=\"[^\"]+\"[^>]*)>", Pattern.CASE_INSENSITIVE);
    private static final Pattern SERVER_GROUP = Pattern.compile(
            "<div class=\"type\" data-type=\"([^\"]*)\">(.*?)(?=<div class=\"type\"|</div></div>\\s*$)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern SERVER = Pattern.compile(
            "<li\\s+([^>]*data-link-id=\"[^\"]+\"[^>]*)>(.*?)</li>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ATTRIBUTE = Pattern.compile("([a-zA-Z0-9_-]+)=\"([^\"]*)\"");
    private static final Pattern PLAYER_ID = Pattern.compile(
            "id=[\"']megaplay-player[\"'][^>]*data-id=[\"'](\\d+)[\"']",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ANY_PLAYER_ID = Pattern.compile(
            "\\bdata-id=[\"'](\\d+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern QUALITY = Pattern.compile("(?i)(\\d{3,4})p?");

    private static final Set<String> MEGAPLAY_MEDIA_DOMAINS = Set.of(
            "megaplay.buzz",
            "mewstream.buzz",
            "lostproject.club",
            "voltara.click",
            "kotocdn.site",
            "shiora.top",
            "akirax.buzz"
    );

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
        List<String> queries = media.searchTitles().stream()
                .filter(title -> title != null && !title.isBlank())
                .distinct()
                .limit(4)
                .toList();
        if (queries.isEmpty()) queries = List.of(media.title());

        List<CompletableFuture<List<SourceSeries>>> attempts = queries.stream()
                .map(query -> {
                    URI uri = BASE.resolve("filter/?keyword="
                            + URLEncoder.encode(query, StandardCharsets.UTF_8));
                    return http.getHtml(uri, Map.of("Referer", BASE.toString()))
                            .thenApply(AnikotoAnimeSource::parseSearchResults)
                            .exceptionally(error -> List.of());
                })
                .toList();

        return CompletableFuture.allOf(attempts.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> {
                    Map<String, SourceSeries> merged = new LinkedHashMap<>();
                    for (CompletableFuture<List<SourceSeries>> attempt : attempts) {
                        for (SourceSeries series : attempt.join()) {
                            merged.putIfAbsent(series.seriesId(), series);
                        }
                    }
                    return List.copyOf(merged.values());
                });
    }

    @Override
    public CompletableFuture<List<SourceEpisode>> loadEpisodes(AniMedia media, SourceSeries series) {
        URI watch = URI.create(series.link());
        return http.getHtml(watch, Map.of("Referer", BASE.toString())).thenCompose(html -> {
            Matcher id = MEDIA_ID.matcher(html);
            if (!id.find()) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "Anikoto no longer exposes a media identifier for this title."));
            }
            URI endpoint = BASE.resolve("ajax/episode/list/" + id.group(1));
            return http.get(endpoint, headers(watch)).thenApply(json -> {
                if (json.path("status").asInt() != 200) {
                    throw new IllegalStateException(
                            json.path("message").asText("Anikoto episode request failed."));
                }
                return parseEpisodes(series, json.path("result").asText());
            });
        });
    }

    @Override
    public CompletableFuture<List<VideoServer>> loadVideoServers(
            AniMedia media, SourceSeries series, SourceEpisode episode) {
        String ids = episode.extra().getOrDefault("serverIds", "");
        if (ids.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Anikoto returned no servers for Episode " + episode.number() + "."));
        }
        URI endpoint = BASE.resolve("ajax/server/list?servers="
                + URLEncoder.encode(ids, StandardCharsets.UTF_8));
        return http.get(endpoint, headers(URI.create(series.link()))).thenApply(json -> {
            if (json.path("status").asInt() != 200) {
                throw new IllegalStateException(
                        json.path("message").asText("Anikoto server request failed."));
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
                throw new IllegalStateException(
                        json.path("message").asText("Anikoto player request failed."));
            }
            String raw = json.path("result").path("url").asText("");
            if (raw.isBlank()) {
                throw new IllegalStateException("Anikoto returned an empty player URL.");
            }
            URI embed = URI.create(raw);
            return resolveMegaPlay(embed, server);
        });
    }

    private CompletableFuture<ResolvedServer> resolveMegaPlay(URI embed, VideoServer server) {
        String playerOrigin = origin(embed);
        Map<String, String> playerHeaders = new LinkedHashMap<>();
        playerHeaders.put("Accept", "text/html,application/json,text/plain,*/*");
        playerHeaders.put("Referer", playerOrigin + "/");
        playerHeaders.put("User-Agent", BROWSER_USER_AGENT);

        return http.getHtml(embed, playerHeaders).thenCompose(html -> {
            String dataId = extractPlayerId(html);
            if (dataId.isBlank()) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "The Anikoto video host no longer exposes its player identifier."));
            }

            // MegaPlay currently returns an encrypted native-source payload from this endpoint.
            // Decode it below while retaining support for older plain source response shapes.
            StringBuilder sourceUrl = new StringBuilder(playerOrigin)
                    .append("/stream/getSourcesNew?id=")
                    .append(URLEncoder.encode(dataId, StandardCharsets.UTF_8));
            String cdn = queryParameter(embed, "s");
            if (!cdn.isBlank()) {
                sourceUrl.append("&s=").append(URLEncoder.encode(cdn, StandardCharsets.UTF_8));
            }
            URI sourceEndpoint = URI.create(sourceUrl.toString());
            Map<String, String> sourceHeaders = new LinkedHashMap<>();
            sourceHeaders.put("Referer", embed.toString());
            sourceHeaders.put("X-Requested-With", "XMLHttpRequest");
            sourceHeaders.put("User-Agent", BROWSER_USER_AGENT);

            return http.get(sourceEndpoint, sourceHeaders)
                    .thenApply(json -> parsePlayerSources(server, embed, json))
                    .thenCompose(this::validatePlayerStream);
        });
    }

    private CompletableFuture<ResolvedServer> validatePlayerStream(ResolvedServer resolved) {
        if (resolved.videos().isEmpty()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("MegaPlay resolved no native video variants."));
        }
        PlaybackSource selected = resolved.videos().get(0);
        if (!"hls".equalsIgnoreCase(selected.container())
                && !selected.uri().getPath().toLowerCase().endsWith(".m3u8")) {
            return CompletableFuture.completedFuture(resolved);
        }
        Map<String, String> validationHeaders = new LinkedHashMap<>(selected.headers());
        validationHeaders.remove("Origin");
        return http.getText(selected.uri(), validationHeaders).thenApply(manifest -> {
            String normalized = manifest == null ? "" : manifest.replaceFirst("^\\uFEFF", "").stripLeading();
            if (!normalized.startsWith("#EXTM3U")) {
                throw new IllegalStateException("MegaPlay returned an invalid HLS manifest.");
            }
            return resolved;
        });
    }

    static ResolvedServer parsePlayerSources(VideoServer server, URI embed, JsonNode json) {
        List<SourceCandidate> candidates = new ArrayList<>();
        collectSourceCandidates(json, candidates);
        String encryptedPayload = json.path("enc").asText("");
        if (!encryptedPayload.isBlank()) {
            collectSourceCandidates(decryptMegaPlayPayload(encryptedPayload), candidates);
        }

        List<PlaybackSource> videos = new ArrayList<>();
        Set<String> seenVideos = new LinkedHashSet<>();
        for (SourceCandidate candidate : candidates) {
            URI uri = absoluteHttpUri(candidate.url());
            if (uri == null || !seenVideos.add(uri.toString())) continue;

            String container = container(uri);
            String label = candidate.label().isBlank() ? server.name() : candidate.label();
            videos.add(new PlaybackSource(
                    uri,
                    quality(candidate.label()),
                    container,
                    null,
                    label,
                    mediaHeaders(embed, uri)
            ));
        }

        if (videos.isEmpty()) {
            String fields = topLevelFields(json);
            throw new IllegalStateException(
                    "MegaPlay returned no supported native stream"
                            + (fields.isBlank() ? "." : " (response fields: " + fields + ")."));
        }

        List<SubtitleTrack> subtitles = new ArrayList<>();
        Set<String> seenSubtitles = new LinkedHashSet<>();
        collectSubtitleTracks(json, embed, subtitles, seenSubtitles);
        return new ResolvedServer(server, videos, subtitles, List.of());
    }

    static JsonNode decryptMegaPlayPayload(String encryptedPayload) {
        try {
            byte[] encrypted = Base64.getUrlDecoder().decode(encryptedPayload);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(MEGAPLAY_KEY, "AES"), new IvParameterSpec(MEGAPLAY_IV));
            JsonNode decoded = JSON.readTree(new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8));
            if (decoded == null || decoded.isNull()) {
                throw new IllegalStateException("MegaPlay's decoded stream response was empty.");
            }
            return decoded;
        } catch (IllegalStateException error) {
            throw error;
        } catch (GeneralSecurityException | IllegalArgumentException | java.io.IOException error) {
            throw new IllegalStateException(
                    "MegaPlay's encrypted stream response could not be decoded.", error);
        }
    }

    private static byte[] paddedKey(String value, int length) {
        byte[] result = new byte[length];
        byte[] source = value.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(source, 0, result, 0, Math.min(source.length, result.length));
        return result;
    }

    private static void collectSourceCandidates(JsonNode node, List<SourceCandidate> out) {
        if (node == null || node.isMissingNode() || node.isNull()) return;
        if (node.isTextual()) {
            String value = node.asText("").trim();
            if (!value.isBlank()) out.add(new SourceCandidate(value, ""));
            return;
        }
        if (node.isArray()) {
            node.forEach(child -> collectSourceCandidates(child, out));
            return;
        }
        if (!node.isObject()) return;

        String url = firstText(node, "file", "url", "src");
        if (!url.isBlank()) {
            out.add(new SourceCandidate(url, firstText(node, "label", "quality")));
        }
        for (String key : List.of("sources", "source", "links", "data")) {
            JsonNode child = node.get(key);
            if (child != null) collectSourceCandidates(child, out);
        }
    }

    private static void collectSubtitleTracks(
            JsonNode node,
            URI embed,
            List<SubtitleTrack> out,
            Set<String> seen
    ) {
        if (node == null || node.isMissingNode() || node.isNull()) return;
        if (node.isArray()) {
            node.forEach(child -> collectSubtitleTracks(child, embed, out, seen));
            return;
        }
        if (!node.isObject()) return;

        String kind = firstText(node, "kind", "type").toLowerCase();
        String url = firstText(node, "file", "url", "src");
        boolean subtitleLike = kind.isBlank()
                || kind.contains("caption")
                || kind.contains("subtitle")
                || kind.equals("sub");
        URI uri = absoluteHttpUri(url);
        if (subtitleLike && uri != null && seen.add(uri.toString())) {
            String label = firstText(node, "label", "title", "language");
            if (label.isBlank()) label = "Subtitle";
            out.add(new SubtitleTrack(
                    label,
                    uri,
                    node.path("default").asBoolean(false),
                    mediaHeaders(embed, uri)
            ));
        }

        for (String key : List.of("tracks", "captions", "subtitles", "data")) {
            JsonNode child = node.get(key);
            if (child != null) collectSubtitleTracks(child, embed, out, seen);
        }
    }

    private static Map<String, String> mediaHeaders(URI embed, URI media) {
        String playerOrigin = origin(embed);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Referer", playerOrigin + "/");
        headers.put("User-Agent", BROWSER_USER_AGENT);
        if (isMegaPlayMediaHost(media.getHost())) {
            headers.put("Origin", playerOrigin);
        }
        return Map.copyOf(headers);
    }

    static boolean isMegaPlayMediaHost(String host) {
        if (host == null || host.isBlank()) return false;
        String normalized = host.toLowerCase().replaceFirst("\\.$", "");
        return MEGAPLAY_MEDIA_DOMAINS.stream()
                .anyMatch(domain -> normalized.equals(domain) || normalized.endsWith("." + domain));
    }

    private static String extractPlayerId(String html) {
        String value = html == null ? "" : html;
        Matcher exact = PLAYER_ID.matcher(value);
        if (exact.find()) return exact.group(1);
        Matcher fallback = ANY_PLAYER_ID.matcher(value);
        return fallback.find() ? fallback.group(1) : "";
    }

    private static String origin(URI uri) {
        if (uri == null || uri.getScheme() == null || uri.getAuthority() == null) {
            throw new IllegalArgumentException("A valid MegaPlay embed URL is required.");
        }
        return uri.getScheme() + "://" + uri.getAuthority();
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

    private static URI absoluteHttpUri(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            URI uri = URI.create(value.trim());
            if (!uri.isAbsolute() || uri.getHost() == null) return null;
            if (!"http".equalsIgnoreCase(uri.getScheme())
                    && !"https".equalsIgnoreCase(uri.getScheme())) return null;
            return uri;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String container(URI uri) {
        String text = uri == null ? "" : uri.toString().toLowerCase();
        if (text.contains(".m3u8")) return "hls";
        if (text.contains(".mp4")) return "mp4";
        return "hls";
    }

    private static Integer quality(String label) {
        if (label == null || label.isBlank()) return null;
        Matcher matcher = QUALITY.matcher(label);
        if (!matcher.find()) return null;
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String firstText(JsonNode node, String... fields) {
        if (node == null || !node.isObject()) return "";
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && value.isValueNode()) {
                String text = value.asText("").trim();
                if (!text.isBlank()) return text;
            }
        }
        return "";
    }

    private static String topLevelFields(JsonNode json) {
        if (json == null || !json.isObject()) return "";
        List<String> names = new ArrayList<>();
        json.fieldNames().forEachRemaining(names::add);
        return String.join(", ", names.stream().limit(8).toList());
    }

    static List<SourceSeries> parseSearchResults(String html) {
        List<SourceSeries> result = new ArrayList<>();
        Matcher matcher = SEARCH_ITEM.matcher(html == null ? "" : html);
        while (matcher.find()) {
            String title = text(matcher.group(4));
            String link = matcher.group(3).trim();
            String id = link.replaceFirst("(?i)^https?://[^/]+/watch/", "")
                    .replaceFirst("/ep-[^/?#]+.*$", "");
            result.add(new SourceSeries(
                    "anikoto",
                    id,
                    title,
                    link,
                    matcher.group(1),
                    List.of(text(matcher.group(2))),
                    null,
                    Map.of()
            ));
        }
        return List.copyOf(result);
    }

    static List<SourceEpisode> parseEpisodes(SourceSeries series, String html) {
        List<SourceEpisode> result = new ArrayList<>();
        Matcher matcher = EPISODE.matcher(html == null ? "" : html);
        while (matcher.find()) {
            Map<String, String> attributes = attributes(matcher.group(1));
            String number = attributes.getOrDefault(
                    "data-num", attributes.getOrDefault("data-slug", ""));
            if (number.isBlank()) continue;
            Map<String, String> extra = new LinkedHashMap<>();
            extra.put("serverIds", attributes.getOrDefault("data-ids", ""));
            extra.put("sub", attributes.getOrDefault("data-sub", "0"));
            extra.put("dub", attributes.getOrDefault("data-dub", "0"));
            result.add(new SourceEpisode(
                    "anikoto",
                    series.seriesId(),
                    attributes.getOrDefault("data-id", number),
                    number,
                    "Episode " + number,
                    "",
                    "",
                    false,
                    languageGroup(extra),
                    extra
            ));
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
                result.add(new VideoServer(
                        "anikoto",
                        attributes.getOrDefault("data-sv-id", reference),
                        name + (language.isBlank() ? "" : " · " + language.toUpperCase()),
                        reference,
                        Map.of("language", language)
                ));
            }
        }
        return List.copyOf(result);
    }

    private static Map<String, String> headers(URI referer) {
        Map<String, String> result = new LinkedHashMap<>(AJAX_HEADERS);
        result.put("Referer", referer.toString());
        return result;
    }

    private static Map<String, String> attributes(String raw) {
        Map<String, String> result = new LinkedHashMap<>();
        Matcher matcher = ATTRIBUTE.matcher(raw == null ? "" : raw);
        while (matcher.find()) {
            result.put(matcher.group(1).toLowerCase(), htmlDecode(matcher.group(2)));
        }
        return result;
    }

    private static String languageGroup(Map<String, String> extra) {
        boolean sub = "1".equals(extra.get("sub"));
        boolean dub = "1".equals(extra.get("dub"));
        return sub && dub ? "SUB · DUB" : dub ? "DUB" : sub ? "SUB" : "";
    }

    private static String text(String html) {
        return htmlDecode((html == null ? "" : html)
                .replaceAll("<[^>]+>", " ")
                .replaceAll("\\s+", " ")
                .trim());
    }

    private static String htmlDecode(String value) {
        return value.replace("&amp;amp;", "&")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">");
    }

    private record SourceCandidate(String url, String label) {}
}
