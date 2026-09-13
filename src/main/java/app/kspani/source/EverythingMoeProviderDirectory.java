package app.kspani.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ranked anime-streaming directory backed by EverythingMoe.
 *
 * Directory entries are recommendation metadata only. They become in-app playback
 * sources only when a configured AnimeSource exposes a matching id, name, or domain.
 */
public final class EverythingMoeProviderDirectory {
    public static final URI DIRECTORY_URI = URI.create("https://everythingmoe.com/");
    public static final URI LOW_RANKS_URI = URI.create("https://everythingmoe.com/data/lowsec/anime.json");

    private static final Pattern ANIME_SECTION = Pattern.compile(
            "(?is)Anime(?:\\s|<[^>]+>)+Streaming(.*?)(?:Donghua(?:\\s|<[^>]+>)+Streaming|Manga(?:\\s|<[^>]+>)+Reading)");
    private static final Pattern ENTRY = Pattern.compile(
            "(?is)<a[^>]+href\\s*=\\s*[\\\"'](/s/[^\\\"'#?]+)[\\\"'][^>]*>(.*?)</a>");
    private static final Pattern DATA_LINK = Pattern.compile(
            "(?is)data-link\\s*=\\s*[\\\"']([^\\\"']+)[\\\"']");
    private static final Pattern TAGS = Pattern.compile("(?is)<[^>]+>");
    private static final Set<String> MULTI_SOURCE_IDS = Set.of("miruro", "anisnatch", "otakuu");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<Entry> FALLBACK = loadBundledFallback();

    private final SourceRegistry sources;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private volatile List<ProviderSite> latest;

    public EverythingMoeProviderDirectory(SourceRegistry sources) {
        this.sources = sources;
        this.latest = classify(FALLBACK, false);
    }

    public List<ProviderSite> fallbackSnapshot() {
        return classify(FALLBACK, false);
    }

    public List<ProviderSite> latestSnapshot() {
        return latest;
    }

    /** Highest-ranked entry in the latest successful directory snapshot. */
    public Optional<ProviderSite> recommendedSite() {
        return latestSnapshot().stream().findFirst();
    }

    public CompletableFuture<List<ProviderSite>> refresh() {
        HttpRequest pageRequest = HttpRequest.newBuilder(DIRECTORY_URI)
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "text/html,application/xhtml+xml")
                .header("User-Agent", "Aokuvue/1.6")
                .GET()
                .build();
        HttpRequest lowRanksRequest = HttpRequest.newBuilder(LOW_RANKS_URI)
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("User-Agent", "Aokuvue/1.6")
                .GET()
                .build();
        var page = client.sendAsync(pageRequest, HttpResponse.BodyHandlers.ofString());
        var lowRanks = client.sendAsync(lowRanksRequest, HttpResponse.BodyHandlers.ofString());
        return page.thenCombine(lowRanks, (pageResponse, lowResponse) -> {
                    List<Entry> top = successful(pageResponse) ? parseEntries(pageResponse.body()) : List.of();
                    List<Entry> low = successful(lowResponse) ? parseJsonEntries(lowResponse.body()) : List.of();
                    List<Entry> entries = merge(top, low);

                    // The EverythingMoe directory is live data: the number of entries changes as
                    // providers are added/removed. Requiring it to exactly match the bundled
                    // fallback size made otherwise healthy live snapshots look incomplete.
                    boolean usableLiveSnapshot = hasUsableLiveSnapshot(entries);
                    List<ProviderSite> resolved = classify(
                            usableLiveSnapshot ? entries : FALLBACK,
                            usableLiveSnapshot);
                    latest = resolved;
                    return resolved;
                })
                .exceptionally(error -> latestSnapshot());
    }

    private static boolean hasUsableLiveSnapshot(List<Entry> entries) {
        if (entries == null || entries.isEmpty()) return false;
        if (FALLBACK.isEmpty()) return entries.size() >= 20;

        // Accept normal directory churn while still rejecting a clearly partial/blocked response.
        int minimum = Math.max(20, (int) Math.ceil(FALLBACK.size() * 0.60));
        return entries.size() >= minimum;
    }

    public OptionalInt rankFor(AnimeSource source) {
        if (source == null) return OptionalInt.empty();
        List<String> tokens = new ArrayList<>();
        tokens.add(normalize(source.descriptor().id()));
        tokens.add(normalize(source.descriptor().name()));
        source.providerDomains().stream().map(EverythingMoeProviderDirectory::normalizeHost)
                .map(EverythingMoeProviderDirectory::normalize).forEach(tokens::add);
        List<ProviderSite> ranked = latestSnapshot();
        for (int i = 0; i < ranked.size(); i++) {
            ProviderSite site = ranked.get(i);
            String siteId = normalize(site.id());
            String siteName = normalize(site.name());
            boolean matches = tokens.stream().filter(token -> !token.isBlank()).anyMatch(token ->
                    siteId.contains(token) || token.contains(siteId)
                            || siteName.contains(token) || token.contains(siteName));
            if (matches) return OptionalInt.of(i + 1);
        }
        return OptionalInt.empty();
    }

    List<Entry> parseEntries(String html) {
        if (html == null || html.isBlank()) return List.of();
        Matcher section = ANIME_SECTION.matcher(html);
        if (!section.find()) return List.of();
        Map<String, Entry> entries = new LinkedHashMap<>();
        Matcher matcher = ENTRY.matcher(section.group(1));
        while (matcher.find()) {
            String path = matcher.group(1).trim();
            String slug = path.substring(path.lastIndexOf('/') + 1);
            String name = decodeEntities(TAGS.matcher(matcher.group(2)).replaceAll("").trim());
            if (slug.isBlank() || name.isBlank() || "animekai".equalsIgnoreCase(slug)) continue;
            Matcher link = DATA_LINK.matcher(matcher.group(0));
            URI destination = link.find() ? safeUri(decodeEntities(link.group(1))) : null;
            String id = normalize(slug);
            entries.putIfAbsent(id, new Entry(slug, name, destination, MULTI_SOURCE_IDS.contains(id)));
        }
        return List.copyOf(entries.values());
    }

    List<Entry> parseJsonEntries(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            JsonNode root = MAPPER.readTree(json);
            if (!root.isArray()) return List.of();
            List<Entry> entries = new ArrayList<>();
            for (JsonNode node : root) {
                String id = node.path("id").asText("").trim();
                String name = node.path("title").asText("").trim();
                URI destination = safeUri(node.path("link").asText(""));
                String tags = node.path("tags").asText("").toLowerCase(Locale.ROOT);
                if (!id.isBlank() && !name.isBlank()) {
                    entries.add(new Entry(id, name, destination, tags.contains("mult")));
                }
            }
            return List.copyOf(entries);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static boolean successful(HttpResponse<?> response) {
        return response != null && response.statusCode() >= 200 && response.statusCode() < 300;
    }

    private static List<Entry> merge(List<Entry> top, List<Entry> low) {
        Map<String,Entry> merged = new LinkedHashMap<>();
        if (top != null) top.forEach(entry -> merged.putIfAbsent(normalize(entry.slug()), entry));
        if (low != null) low.forEach(entry -> merged.putIfAbsent(normalize(entry.slug()), entry));
        return List.copyOf(merged.values());
    }

    private static List<Entry> loadBundledFallback() {
        try (InputStream input = EverythingMoeProviderDirectory.class
                .getResourceAsStream("/everythingmoe-anime-fallback.json")) {
            if (input == null) return List.of();
            JsonNode root = MAPPER.readTree(input);
            List<Entry> entries = new ArrayList<>();
            for (JsonNode node : root) {
                String id = node.path("id").asText("").trim();
                String name = node.path("title").asText("").trim();
                URI destination = safeUri(node.path("link").asText(""));
                String tags = node.path("tags").asText("").toLowerCase(Locale.ROOT);
                if (!id.isBlank() && !name.isBlank()) {
                    entries.add(new Entry(id, name, destination, tags.contains("mult")));
                }
            }
            return List.copyOf(entries);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static URI safeUri(String value) {
        try {
            URI uri = URI.create(value == null ? "" : value.trim());
            return uri.isAbsolute() ? uri : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private List<ProviderSite> classify(List<Entry> entries, boolean live) {
        List<ProviderSite> out = new ArrayList<>();
        for (int index = 0; index < entries.size(); index++) {
            Entry entry = entries.get(index);
            String id = normalize(entry.slug());
            boolean direct = sources.playbackSources().stream().anyMatch(source -> matches(source, id, entry.name()));
            out.add(new ProviderSite(
                    id,
                    entry.name(),
                    entry.destination() == null ? URI.create(DIRECTORY_URI + "s/" + entry.slug()) : entry.destination(),
                    direct ? ProviderAccess.DIRECT_PLUGIN : ProviderAccess.CATALOG_ONLY,
                    live,
                    index + 1,
                    entry.multiSource()
            ));
        }
        return List.copyOf(out);
    }

    private static boolean matches(AnimeSource source, String siteId, String siteName) {
        List<String> tokens = new ArrayList<>();
        tokens.add(normalize(source.descriptor().id()));
        tokens.add(normalize(source.descriptor().name()));
        source.providerDomains().stream().map(EverythingMoeProviderDirectory::normalizeHost)
                .map(EverythingMoeProviderDirectory::normalize).forEach(tokens::add);
        String normalizedName = normalize(siteName);
        return tokens.stream().filter(token -> !token.isBlank()).anyMatch(token ->
                siteId.contains(token) || token.contains(siteId)
                        || normalizedName.contains(token) || token.contains(normalizedName));
    }

    private static String decodeEntities(String value) {
        return value.replace("&amp;", "&").replace("&#39;", "'")
                .replace("&quot;", "\"").replace("&nbsp;", " ").trim();
    }

    private static String normalizeHost(String host) {
        if (host == null) return "";
        String value = host.toLowerCase(Locale.ROOT).trim();
        while (value.startsWith("www.")) value = value.substring(4);
        return value;
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    record Entry(String slug, String name, URI destination, boolean multiSource) {}
}
