package app.kspani.source;

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
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Live EverythingMoe Manga Reading directory with a small resilient fallback snapshot. */
public final class EverythingMoeMangaDirectory {
    public static final URI DIRECTORY_URI = URI.create("https://everythingmoe.com/section/manga");

    private static final Pattern ENTRY = Pattern.compile(
            "(?is)<a[^>]+href\\s*=\\s*[\\\"'](/s/[^\\\"'#?]+)[\\\"'][^>]*>(.*?)</a>");
    private static final Pattern DATA_LINK = Pattern.compile(
            "(?is)data-link\\s*=\\s*[\\\"']([^\\\"']+)[\\\"']");
    private static final Pattern TAGS = Pattern.compile("(?is)<[^>]+>");

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private volatile List<ProviderSite> latest = fallbackSnapshot();

    public List<ProviderSite> latestSnapshot() { return latest; }
    public List<ProviderSite> fallbackSnapshot() { return classify(fallbackEntries(), false); }

    public CompletableFuture<List<ProviderSite>> refresh() {
        HttpRequest request = HttpRequest.newBuilder(DIRECTORY_URI)
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "text/html,application/xhtml+xml")
                .header("User-Agent", "Aokuvue/1.5.24")
                .GET().build();
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) return latest;
                    List<Entry> parsed = parseEntries(response.body());
                    if (parsed.size() < 20) return latest;
                    latest = classify(parsed, true);
                    return latest;
                })
                .exceptionally(error -> latest);
    }

    List<Entry> parseEntries(String html) {
        if (html == null || html.isBlank()) return List.of();
        Map<String, Entry> entries = new LinkedHashMap<>();
        Matcher matcher = ENTRY.matcher(html);
        while (matcher.find()) {
            String path = matcher.group(1).trim();
            String slug = path.substring(path.lastIndexOf('/') + 1);
            String body = matcher.group(2);
            String name = decode(TAGS.matcher(body).replaceAll(" ").replaceAll("\\s+", " ").trim());
            if (slug.isBlank() || name.isBlank()) continue;
            Matcher destination = DATA_LINK.matcher(matcher.group(0));
            URI uri = destination.find() ? safeUri(decode(destination.group(1))) : null;
            boolean multi = body.toUpperCase(Locale.ROOT).contains("MULT");
            entries.putIfAbsent(normalize(slug), new Entry(slug, name, uri, multi));
        }
        return List.copyOf(entries.values());
    }

    private static List<ProviderSite> classify(List<Entry> entries, boolean live) {
        List<ProviderSite> out = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            URI uri = entry.destination() == null
                    ? URI.create("https://everythingmoe.com/s/" + entry.slug())
                    : entry.destination();
            out.add(new ProviderSite(normalize(entry.slug()), entry.name(), uri,
                    ProviderAccess.CATALOG_ONLY, live, i + 1, entry.multiSource()));
        }
        return List.copyOf(out);
    }

    private static List<Entry> fallbackEntries() {
        return List.of(
                entry("comix", "Comix"), entry("mangadotnet", "Mangadotnet"),
                entry("atsumaru", "Atsumaru"), entry("mangaball", "Mangaball"),
                entry("onisaga", "OniSaga"), entry("mangafire", "MangaFire"),
                entry("weebcentral", "Weeb Central"), entry("mangago", "Mangago"),
                entry("mkissamanga", "MKissa Manga"), entry("bookwalker", "Bookwalker"),
                entry("rakutenkobo", "Rakuten Kobo"), entry("mangataro", "MangaTaro"),
                entry("mangacloud", "MangaCloud"), entry("mangakatana", "MangaKatana"),
                entry("mangak", "MangaK"), entry("xcomic", "XComic"),
                new Entry("cubariproxy", "Cubari Proxy", null, true), entry("kaliscan", "KaliScan"),
                entry("vymanga", "VyManga"), entry("likemanga", "LikeManga"),
                entry("mangaplus", "MANGA Plus")
        );
    }

    private static Entry entry(String slug, String name) { return new Entry(slug, name, null, false); }
    private static URI safeUri(String value) {
        try { URI uri = URI.create(value == null ? "" : value.trim()); return uri.isAbsolute() ? uri : null; }
        catch (Exception ignored) { return null; }
    }
    private static String decode(String value) {
        return value.replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\"").replace("&nbsp;", " ").trim();
    }
    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    record Entry(String slug, String name, URI destination, boolean multiSource) {}
}
