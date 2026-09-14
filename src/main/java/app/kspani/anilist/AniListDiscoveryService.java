package app.kspani.anilist;

import app.kspani.domain.AniMedia;
import app.kspani.domain.MediaType;
import app.kspani.domain.UserListEntry;
import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * AniList-backed discovery service used by the desktop catalog UI.
 *
 * <p>This intentionally exposes AniList pagination instead of pretending a single
 * {@code perPage} response is the complete catalog. AniList caps a page at 50 entries,
 * so callers keep requesting pages until {@link MediaPage#hasNextPage()} is false.</p>
 */
public final class AniListDiscoveryService {
    public static final int PAGE_SIZE = 50;

    private final AniListClient client;
    private final boolean includeAdult;

    public AniListDiscoveryService(AniListClient client, boolean includeAdult) {
        this.client = client;
        this.includeAdult = includeAdult;
    }

    public record CatalogFilter(
            MediaType type,
            String sort,
            String format,
            String source,
            String genre,
            Integer minimumScore,
            Integer year,
            boolean hentai
    ) {
        public CatalogFilter {
            type = type == null ? MediaType.ANIME : type;
            sort = blankToNull(sort) == null ? "POPULARITY_DESC" : sort.trim();
            format = blankToNull(format);
            source = blankToNull(source);
            genre = blankToNull(genre);
        }

        public static CatalogFilter anime() {
            return new CatalogFilter(MediaType.ANIME, "POPULARITY_DESC", null, null, null, null, null, false);
        }

        public static CatalogFilter manga() {
            return new CatalogFilter(MediaType.MANGA, "POPULARITY_DESC", null, null, null, null, null, false);
        }

        public CatalogFilter withFormat(String value) {
            return new CatalogFilter(type, sort, value, source, genre, minimumScore, year, hentai);
        }

        public CatalogFilter withSort(String value) {
            return new CatalogFilter(type, value, format, source, genre, minimumScore, year, hentai);
        }

        public CatalogFilter withYear(Integer value) {
            return new CatalogFilter(type, sort, format, source, genre, minimumScore, value, hentai);
        }
    }

    public record MediaPage(List<AniMedia> items, int page, int total, boolean hasNextPage) {
        public MediaPage {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    public record AiringRelease(long id, long airingAtEpochSeconds, int episode, AniMedia media) {}

    public record AiringPage(List<AiringRelease> items, int page, int total, boolean hasNextPage) {
        public AiringPage {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    public CompletableFuture<List<String>> genres() {
        return client.execute("query { GenreCollection }", Map.of())
                .thenApply(root -> {
                    List<String> out = new ArrayList<>();
                    JsonNode values = root.path("data").path("GenreCollection");
                    if (values.isArray()) {
                        values.forEach(value -> {
                            String genre = value.asText("").trim();
                            if (!genre.isBlank() && !"Hentai".equalsIgnoreCase(genre)) out.add(genre);
                        });
                    }
                    out.sort(String.CASE_INSENSITIVE_ORDER);
                    return List.copyOf(out);
                });
    }

    public CompletableFuture<MediaPage> browse(CatalogFilter filter, int page) {
        int requestedPage = Math.max(1, page);
        List<String> declarations = new ArrayList<>(List.of(
                "$page: Int!", "$perPage: Int!", "$type: MediaType!", "$sort: [MediaSort]"
        ));
        List<String> arguments = new ArrayList<>(List.of(
                "type: $type", "sort: $sort"
        ));

        LinkedHashMap<String, Object> vars = new LinkedHashMap<>();
        vars.put("page", requestedPage);
        vars.put("perPage", PAGE_SIZE);
        vars.put("type", filter.type().name());
        vars.put("sort", List.of(filter.sort()));

        // AniList rejects null values for comparison operators such as *_greater and *_like.
        // Build only the operators that are active instead of binding a collection of nulls.
        if (filter.type() == MediaType.ANIME && !filter.hentai()) {
            arguments.add("genre_not_in: [\"Hentai\"]");
        }
        if (!includeAdult && !filter.hentai()) {
            addArgument(declarations, arguments, vars, "$isAdult: Boolean", "isAdult: $isAdult", "isAdult", false);
        }
        if (filter.format() != null) {
            addArgument(declarations, arguments, vars, "$format: MediaFormat", "format: $format", "format", filter.format());
        }
        if (filter.source() != null) {
            addArgument(declarations, arguments, vars, "$source: MediaSource", "source: $source", "source", filter.source());
        }
        String selectedGenre = filter.hentai() ? "Hentai" : filter.genre();
        if (selectedGenre != null) {
            addArgument(declarations, arguments, vars, "$genre: String", "genre: $genre", "genre", selectedGenre);
        }
        if (filter.minimumScore() != null) {
            addArgument(declarations, arguments, vars, "$minimumScore: Int", "averageScore_greater: $minimumScore",
                    "minimumScore", filter.minimumScore());
        }
        if (filter.year() != null) {
            addArgument(declarations, arguments, vars, "$year: String", "startDate_like: $year", "year", filter.year() + "%");
        }

        String query = "query(" + String.join(", ", declarations) + ") {\n"
                + "  Page(page: $page, perPage: $perPage) {\n"
                + "    pageInfo { total currentPage hasNextPage }\n"
                + "    media(" + String.join(", ", arguments) + ") {\n"
                + mediaFields() + "\n"
                + "    }\n"
                + "  }\n"
                + "}";

        return client.execute(query, vars).thenApply(root -> {
            JsonNode pageNode = root.path("data").path("Page");
            JsonNode info = pageNode.path("pageInfo");
            List<AniMedia> items = parseMediaArray(pageNode.path("media"));
            return new MediaPage(
                    items,
                    info.path("currentPage").asInt(requestedPage),
                    info.path("total").asInt(items.size()),
                    info.path("hasNextPage").asBoolean(false)
            );
        });
    }

    private static void addArgument(
            List<String> declarations,
            List<String> arguments,
            Map<String, Object> variables,
            String declaration,
            String argument,
            String variable,
            Object value
    ) {
        declarations.add(declaration);
        arguments.add(argument);
        variables.put(variable, value);
    }

    public CompletableFuture<AiringPage> airing(long fromEpochSeconds, long toEpochSeconds, int page) {
        int requestedPage = Math.max(1, page);
        String query = """
                query($page: Int!, $perPage: Int!, $from: Int!, $to: Int!) {
                  Page(page: $page, perPage: $perPage) {
                    pageInfo { total currentPage hasNextPage }
                    airingSchedules(
                      notYetAired: true,
                      airingAt_greater: $from,
                      airingAt_lesser: $to,
                      sort: TIME
                    ) {
                      id
                      airingAt
                      episode
                      media { %s }
                    }
                  }
                }
                """.formatted(mediaFields());
        Map<String, Object> vars = Map.of(
                "page", requestedPage,
                "perPage", PAGE_SIZE,
                "from", safeEpochInt(fromEpochSeconds),
                "to", safeEpochInt(toEpochSeconds)
        );

        return client.execute(query, vars).thenApply(root -> {
            JsonNode pageNode = root.path("data").path("Page");
            JsonNode info = pageNode.path("pageInfo");
            List<AiringRelease> out = new ArrayList<>();
            JsonNode schedules = pageNode.path("airingSchedules");
            if (schedules.isArray()) {
                for (JsonNode schedule : schedules) {
                    JsonNode mediaNode = schedule.path("media");
                    if (mediaNode.isMissingNode() || mediaNode.isNull()) continue;
                    AniMedia media = parseMedia(mediaNode);
                    if (media.genres().stream().anyMatch(value -> "Hentai".equalsIgnoreCase(value))) continue;
                    if (!includeAdult && mediaNode.path("isAdult").asBoolean(false)) continue;
                    out.add(new AiringRelease(
                            schedule.path("id").asLong(),
                            schedule.path("airingAt").asLong(),
                            schedule.path("episode").asInt(),
                            media
                    ));
                }
            }
            return new AiringPage(
                    out,
                    info.path("currentPage").asInt(requestedPage),
                    info.path("total").asInt(out.size()),
                    info.path("hasNextPage").asBoolean(false)
            );
        });
    }

    private static String mediaFields() {
        return """
                id
                type
                isAdult
                title { english romaji native }
                coverImage { extraLarge large }
                bannerImage
                description(asHtml: false)
                format
                status
                season
                seasonYear
                episodes
                chapters
                duration
                averageScore
                meanScore
                popularity
                favourites
                trending
                genres
                source(version: 3)
                startDate { year month day }
                endDate { year month day }
                nextAiringEpisode { episode airingAt }
                siteUrl
                """;
    }

    private static List<AniMedia> parseMediaArray(JsonNode array) {
        if (!array.isArray()) return List.of();
        List<AniMedia> out = new ArrayList<>();
        for (JsonNode node : array) {
            try {
                out.add(parseMedia(node));
            } catch (RuntimeException ignored) {
                // One malformed AniList entry should not make an entire catalog page unusable.
            }
        }
        return out;
    }

    private static AniMedia parseMedia(JsonNode node) {
        JsonNode title = node.path("title");
        String english = title.path("english").asText("");
        String romaji = title.path("romaji").asText("");
        String nativeTitle = title.path("native").asText("");
        String display = first(english, romaji, nativeTitle, "Untitled");
        MediaType type = "MANGA".equalsIgnoreCase(node.path("type").asText(""))
                ? MediaType.MANGA
                : MediaType.ANIME;

        List<String> genres = new ArrayList<>();
        if (node.path("genres").isArray()) {
            node.path("genres").forEach(value -> {
                String genre = value.asText("").trim();
                if (!genre.isBlank()) genres.add(genre);
            });
        }

        JsonNode airing = node.path("nextAiringEpisode");
        String siteUrl = node.path("siteUrl").asText("").trim();
        URI siteUri = null;
        if (!siteUrl.isBlank()) {
            try { siteUri = URI.create(siteUrl); } catch (IllegalArgumentException ignored) {}
        }

        return new AniMedia(
                node.path("id").asInt(),
                type,
                display,
                english,
                romaji,
                nativeTitle,
                List.of(),
                first(node.path("coverImage").path("extraLarge").asText(""), node.path("coverImage").path("large").asText(""), ""),
                node.path("bannerImage").asText(""),
                node.path("description").asText(""),
                node.path("format").asText(""),
                node.path("status").asText(""),
                node.path("season").asText(""),
                nullableInt(node, "seasonYear"),
                nullableInt(node, "episodes"),
                nullableInt(node, "chapters"),
                nullableInt(node, "duration"),
                nullableInt(node, "averageScore"),
                nullableInt(node, "meanScore"),
                nullableInt(node, "popularity"),
                nullableInt(node, "favourites"),
                nullableInt(node, "trending"),
                genres,
                List.of(),
                List.of(),
                node.path("source").asText(""),
                fuzzyDate(node.path("startDate")),
                fuzzyDate(node.path("endDate")),
                nullableInt(airing, "episode"),
                nullableLong(airing, "airingAt"),
                "",
                siteUri,
                List.of(),
                List.of(),
                UserListEntry.empty()
        );
    }

    private static LocalDate fuzzyDate(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        int year = node.path("year").asInt(0);
        if (year <= 0) return null;
        int month = Math.max(1, node.path("month").asInt(1));
        int day = Math.max(1, node.path("day").asInt(1));
        try {
            return LocalDate.of(year, month, day);
        } catch (RuntimeException ignored) {
            return LocalDate.of(year, 1, 1);
        }
    }

    private static Integer nullableInt(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() || value.isMissingNode() ? null : value.asInt();
    }

    private static Long nullableLong(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() || value.isMissingNode() ? null : value.asLong();
    }

    private static String first(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value.trim();
        return "";
    }

    private static String blankToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private static int safeEpochInt(long value) {
        if (value < 0 || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("AniList airing timestamp is outside the supported epoch range: " + value);
        }
        return (int) value;
    }
}
