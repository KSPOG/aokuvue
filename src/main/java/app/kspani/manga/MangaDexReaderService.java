package app.kspani.manga;

import app.kspani.app.JsonHttpClient;
import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Native reader integration for MangaDex, a ranked EverythingMoe Manga Reading source. */
public final class MangaDexReaderService {
    private static final String API = "https://api.mangadex.org";
    private final JsonHttpClient http;

    public MangaDexReaderService(JsonHttpClient http) {
        this.http = http;
    }

    public record MangaMatch(String id, String title, String originalTitle, String status) {
        @Override public String toString() {
            return originalTitle == null || originalTitle.isBlank() || originalTitle.equalsIgnoreCase(title)
                    ? title : title + " · " + originalTitle;
        }
    }

    public record Chapter(String id, String volume, String number, String title, String language,
                          int pages, String group, String publishedAt) {
        @Override public String toString() {
            String prefix = number == null || number.isBlank() ? "Oneshot" : "Chapter " + number;
            return title == null || title.isBlank() ? prefix : prefix + " · " + title;
        }
    }

    public record ChapterPages(String chapterId, List<URI> pages) {}

    public CompletableFuture<List<MangaMatch>> search(String title) {
        Map<String,String> query = new LinkedHashMap<>();
        query.put("title", title);
        query.put("limit", "10");
        query.put("availableTranslatedLanguage[]", "en");
        query.put("order[relevance]", "desc");
        URI uri = endpoint("/manga", query);
        return http.get(uri, Map.of()).thenApply(MangaDexReaderService::parseMatches);
    }

    public CompletableFuture<List<Chapter>> chapters(String mangaId) {
        return chapterPage(mangaId, 0, new ArrayList<>());
    }

    private CompletableFuture<List<Chapter>> chapterPage(String mangaId, int offset, List<Chapter> collected) {
        Map<String,String> query = new LinkedHashMap<>();
        query.put("limit", "100");
        query.put("offset", Integer.toString(offset));
        query.put("translatedLanguage[]", "en");
        query.put("order[volume]", "asc");
        query.put("order[chapter]", "asc");
        query.put("includeExternalUrl", "0");
        query.put("includes[]", "scanlation_group");
        return http.get(endpoint("/manga/" + mangaId + "/feed", query), Map.of()).thenCompose(root -> {
            collected.addAll(parseChapters(root));
            int total = root.path("total").asInt(collected.size());
            int next = offset + root.path("limit").asInt(100);
            if (next < total) return chapterPage(mangaId, next, collected);
            collected.sort(Comparator.comparingDouble(chapter -> numeric(chapter.number())));
            return CompletableFuture.completedFuture(List.copyOf(collected));
        });
    }

    public CompletableFuture<ChapterPages> pages(String chapterId) {
        return http.get(URI.create(API + "/at-home/server/" + chapterId), Map.of())
                .thenApply(root -> parsePages(chapterId, root));
    }

    static List<MangaMatch> parseMatches(JsonNode root) {
        List<MangaMatch> matches = new ArrayList<>();
        for (JsonNode item : root.path("data")) {
            JsonNode attributes = item.path("attributes");
            String english = localized(attributes.path("title"), "en");
            if (english.isBlank()) english = firstText(attributes.path("title"));
            String original = firstText(attributes.path("altTitles"));
            if (!english.isBlank() && !item.path("id").asText().isBlank()) matches.add(new MangaMatch(
                    item.path("id").asText(), english, original, attributes.path("status").asText("")));
        }
        return List.copyOf(matches);
    }

    static List<Chapter> parseChapters(JsonNode root) {
        List<Chapter> chapters = new ArrayList<>();
        for (JsonNode item : root.path("data")) {
            JsonNode attributes = item.path("attributes");
            int pages = attributes.path("pages").asInt(0);
            if (pages <= 0 || !attributes.path("externalUrl").isNull()) continue;
            chapters.add(new Chapter(
                    item.path("id").asText(), text(attributes, "volume"), text(attributes, "chapter"),
                    text(attributes, "title"), attributes.path("translatedLanguage").asText("en"), pages,
                    scanlationGroup(item.path("relationships")), text(attributes, "publishAt")));
        }
        return List.copyOf(chapters);
    }

    static ChapterPages parsePages(String chapterId, JsonNode root) {
        String base = root.path("baseUrl").asText();
        String hash = root.path("chapter").path("hash").asText();
        List<URI> pages = new ArrayList<>();
        if (!base.isBlank() && !hash.isBlank()) {
            for (JsonNode file : root.path("chapter").path("data")) {
                if (!file.asText().isBlank()) pages.add(URI.create(base + "/data/" + hash + "/" + file.asText()));
            }
        }
        if (pages.isEmpty()) throw new IllegalStateException("MangaDex did not return readable pages for this chapter.");
        return new ChapterPages(chapterId, List.copyOf(pages));
    }

    private static URI endpoint(String path, Map<String,String> query) {
        String joined = query.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .reduce((left, right) -> left + "&" + right).orElse("");
        return URI.create(API + path + (joined.isBlank() ? "" : "?" + joined));
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field); return value.isMissingNode() || value.isNull() ? "" : value.asText("");
    }
    private static String localized(JsonNode node, String language) { return node.path(language).asText(""); }
    private static String firstText(JsonNode node) {
        if (node.isObject()) {
            var values = node.elements(); return values.hasNext() ? values.next().asText("") : "";
        }
        if (node.isArray()) for (JsonNode item : node) {
            String value = firstText(item); if (!value.isBlank()) return value;
        }
        return "";
    }
    private static String scanlationGroup(JsonNode relationships) {
        for (JsonNode relationship : relationships) {
            if ("scanlation_group".equals(relationship.path("type").asText())) {
                String name = relationship.path("attributes").path("name").asText("");
                if (!name.isBlank()) return name;
            }
        }
        return "Unknown scanlation group";
    }
    private static double numeric(String value) {
        if (value == null || value.isBlank()) return Double.MAX_VALUE;
        try { return Double.parseDouble(value.replaceAll("[^0-9.]", "")); }
        catch (Exception ignored) { return Double.MAX_VALUE; }
    }
}
