package app.kspani.anilist;

import app.kspani.app.JsonHttpClient;
import app.kspani.config.AppConfig;
import app.kspani.domain.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class AniListClient {
    public static final URI ENDPOINT = URI.create("https://graphql.anilist.co");

    private final AppConfig config;
    private final JsonHttpClient http;
    private final java.util.concurrent.ConcurrentMap<Integer, List<AnimeSeasonRef>> seasonCache = new java.util.concurrent.ConcurrentHashMap<>();

    public AniListClient(AppConfig config, JsonHttpClient http) {
        this.config = config;
        this.http = http;
    }

    public CompletableFuture<Viewer> viewer() {
        String query = """
                query {
                  Viewer { id name siteUrl avatar { large } }
                }
                """;
        return execute(query, Map.of()).thenApply(root -> {
            JsonNode v = root.path("data").path("Viewer");
            if (v.isMissingNode() || v.isNull()) throw new IllegalStateException("AniList did not return Viewer.");
            String site = v.path("siteUrl").asText("");
            return new Viewer(v.path("id").asInt(), v.path("name").asText("AniList User"),
                    v.path("avatar").path("large").asText(""), site.isBlank() ? null : URI.create(site));
        });
    }

    public CompletableFuture<List<AniMedia>> browse(MediaType type, String sort, int limit) {
        String query = """
                query($type: MediaType, $sort: [MediaSort], $perPage: Int) {
                  Page(page: 1, perPage: $perPage) {
                    media(type: $type, sort: $sort%s) { %s }
                  }
                }
                """.formatted(adultFilter(), mediaFields(false));
        Map<String,Object> vars = new LinkedHashMap<>();
        vars.put("type", type.name());
        vars.put("sort", List.of(sort));
        vars.put("perPage", Math.max(1, Math.min(limit, 50)));
        return execute(query, vars).thenApply(root -> parseMediaArray(root.path("data").path("Page").path("media"), null));
    }

    public CompletableFuture<List<AniMedia>> browseAdultAnime(String sort, int limit) {
        String query = """
                query($sort: [MediaSort], $perPage: Int) {
                  Page(page: 1, perPage: $perPage) {
                    media(type: ANIME, isAdult: true, sort: $sort) { %s }
                  }
                }
                """.formatted(mediaFields(false));
        Map<String,Object> vars = new LinkedHashMap<>();
        vars.put("sort", List.of(sort));
        vars.put("perPage", Math.max(1, Math.min(limit, 50)));
        return execute(query, vars).thenApply(root -> parseMediaArray(root.path("data").path("Page").path("media"), null));
    }

    public CompletableFuture<List<AniMedia>> search(String text, MediaType type, int limit) {
        String query = """
                query($search: String, $type: MediaType, $perPage: Int) {
                  Page(page: 1, perPage: $perPage) {
                    media(search: $search, type: $type, sort: SEARCH_MATCH%s) { %s }
                  }
                }
                """.formatted(adultFilter(), mediaFields(false));
        Map<String,Object> vars = Map.of(
                "search", text,
                "type", type.name(),
                "perPage", Math.max(1, Math.min(limit, 50))
        );
        return execute(query, vars).thenApply(root -> parseMediaArray(root.path("data").path("Page").path("media"), null));
    }

    public CompletableFuture<AniMedia> details(int id, MediaType type) {
        String query = """
                query($id: Int, $type: MediaType) {
                  Media(id: $id, type: $type) { %s }
                }
                """.formatted(mediaFields(true));
        return execute(query, Map.of("id", id, "type", type.name()))
                .thenApply(root -> parseMedia(root.path("data").path("Media"), null));
    }


    /**
     * Returns the PREQUEL/SEQUEL chain that represents actual seasons of the same anime.
     * Movies, OVAs, specials and title-unrelated sequel franchises are intentionally excluded.
     */
    public CompletableFuture<List<AnimeSeasonRef>> relatedAnimeSeasons(AniMedia seed) {
        if (seed == null || seed.type() != MediaType.ANIME) return CompletableFuture.completedFuture(List.of());
        List<AnimeSeasonRef> cached = seasonCache.get(seed.id());
        if (cached != null) return CompletableFuture.completedFuture(cached);
        return CompletableFuture.supplyAsync(() -> {
            java.util.LinkedHashMap<Integer, AnimeSeasonRef> seasons = new java.util.LinkedHashMap<>();
            java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
            java.util.HashSet<Integer> visited = new java.util.HashSet<>();
            queue.add(seed.id());

            while (!queue.isEmpty() && visited.size() < 16) {
                int id = queue.removeFirst();
                if (!visited.add(id)) continue;
                JsonNode media = execute(seasonRelationQuery(), Map.of("id", id)).join()
                        .path("data").path("Media");
                if (media.isMissingNode() || media.isNull()) continue;

                AnimeSeasonRef current = parseSeasonRef(media);
                if (id == seed.id() || (SeasonRelationMatcher.isSeasonFormat(current.format()) && SeasonRelationMatcher.sameFamily(seed, titleValues(media.path("title"))))) {
                    seasons.putIfAbsent(current.mediaId(), current);
                }

                JsonNode edges = media.path("relations").path("edges");
                if (!edges.isArray()) continue;
                for (JsonNode edge : edges) {
                    String relation = edge.path("relationType").asText("");
                    if (!"PREQUEL".equals(relation) && !"SEQUEL".equals(relation)) continue;
                    JsonNode node = edge.path("node");
                    if (!"ANIME".equalsIgnoreCase(node.path("type").asText(""))) continue;
                    AnimeSeasonRef ref = parseSeasonRef(node);
                    if (!SeasonRelationMatcher.isSeasonFormat(ref.format())) continue;
                    if (!SeasonRelationMatcher.sameFamily(seed, titleValues(node.path("title")))) continue;
                    seasons.putIfAbsent(ref.mediaId(), ref);
                    if (!visited.contains(ref.mediaId())) queue.addLast(ref.mediaId());
                }
            }

            // Always retain the selected media even if AniList has unusual format metadata.
            seasons.putIfAbsent(seed.id(), new AnimeSeasonRef(
                    seed.id(), seed.title(), seed.format(), seed.season(), seed.seasonYear(),
                    seed.totalEpisodes(), seed.startDate()));

            List<AnimeSeasonRef> out = new ArrayList<>(seasons.values());
            out.sort(java.util.Comparator
                    .comparing((AnimeSeasonRef x) -> x.startDate() == null ? LocalDate.MAX : x.startDate())
                    .thenComparingInt(AnimeSeasonRef::mediaId));
            List<AnimeSeasonRef> result = List.copyOf(out);
            for (AnimeSeasonRef ref : result) seasonCache.put(ref.mediaId(), result);
            return result;
        });
    }

    private static String seasonRelationQuery() {
        return """
                query($id: Int!) {
                  Media(id: $id, type: ANIME) {
                    id type format season seasonYear episodes
                    title { romaji english native }
                    startDate { year month day }
                    relations {
                      edges {
                        relationType
                        node {
                          id type format season seasonYear episodes
                          title { romaji english native }
                          startDate { year month day }
                        }
                      }
                    }
                  }
                }
                """;
    }

    private static AnimeSeasonRef parseSeasonRef(JsonNode node) {
        JsonNode title = node.path("title");
        String display = first(title.path("english").asText(""), title.path("romaji").asText(""),
                title.path("native").asText(""), "Season");
        return new AnimeSeasonRef(
                node.path("id").asInt(), display, node.path("format").asText(""),
                node.path("season").asText(""), nullableInt(node, "seasonYear"),
                nullableInt(node, "episodes"), fuzzyDate(node.path("startDate")));
    }

    private static List<String> titleValues(JsonNode title) {
        return List.of(
                title.path("english").asText(""),
                title.path("romaji").asText(""),
                title.path("native").asText("")
        );
    }

    public CompletableFuture<List<AniMedia>> listCollection(int userId, MediaType type) {
        String query = """
                query($userId: Int!, $type: MediaType!) {
                  MediaListCollection(userId: $userId, type: $type) {
                    lists {
                      name
                      status
                      entries {
                        id status score progress repeat priority private notes customLists
                        startedAt { year month day }
                        completedAt { year month day }
                        media { %s }
                      }
                    }
                  }
                }
                """.formatted(mediaFields(false));
        return execute(query, Map.of("userId", userId, "type", type.name())).thenApply(root -> {
            List<AniMedia> out = new ArrayList<>();
            JsonNode lists = root.path("data").path("MediaListCollection").path("lists");
            if (lists.isArray()) {
                for (JsonNode list : lists) {
                    for (JsonNode entry : list.path("entries")) {
                        UserListEntry state = parseListEntry(entry);
                        out.add(parseMedia(entry.path("media"), state));
                    }
                }
            }
            return out;
        });
    }

    public CompletableFuture<JsonNode> execute(String query, Map<String,?> variables) {
        ObjectNode body = http.mapper().createObjectNode();
        body.put("query", query);
        body.set("variables", http.mapper().valueToTree(variables));
        return http.postJson(ENDPOINT, body, authHeaders()).thenApply(root -> {
            if (root.has("errors") && root.path("errors").isArray() && !root.path("errors").isEmpty()) {
                throw new IllegalStateException(root.path("errors").get(0).path("message").asText("AniList request failed."));
            }
            return root;
        });
    }

    private Map<String,String> authHeaders() {
        String token = config.get("anilist.accessToken");
        return token.isBlank() ? Map.of() : Map.of("Authorization", "Bearer " + token);
    }

    public boolean authenticated() { return !config.get("anilist.accessToken").isBlank(); }

    private String adultFilter() {
        return config.getBoolean("content.includeAdult", true) ? "" : ", isAdult: false";
    }

    private List<AniMedia> parseMediaArray(JsonNode array, UserListEntry override) {
        List<AniMedia> out = new ArrayList<>();
        if (array.isArray()) array.forEach(n -> out.add(parseMedia(n, override)));
        return out;
    }

    private AniMedia parseMedia(JsonNode n, UserListEntry override) {
        if (n == null || n.isMissingNode() || n.isNull()) throw new IllegalStateException("AniList media response was empty.");
        JsonNode title = n.path("title");
        String english = title.path("english").asText("");
        String romaji = title.path("romaji").asText("");
        String nativeTitle = title.path("native").asText("");
        String display = first(english, romaji, nativeTitle, "Untitled");
        String site = n.path("siteUrl").asText("");
        MediaType type = "MANGA".equalsIgnoreCase(n.path("type").asText("ANIME")) ? MediaType.MANGA : MediaType.ANIME;
        List<String> tags = new ArrayList<>();
        if (n.path("tags").isArray()) {
            n.path("tags").forEach(tag -> {
                if (!tag.path("isMediaSpoiler").asBoolean(false) && tag.path("rank").asInt(0) >= 40) {
                    tags.add(tag.path("name").asText(""));
                }
            });
        }
        List<String> studios = new ArrayList<>();
        JsonNode studioNodes = n.path("studios").path("nodes");
        if (studioNodes.isArray()) studioNodes.forEach(s -> studios.add(s.path("name").asText("")));
        String trailer = "";
        JsonNode tr = n.path("trailer");
        if (!tr.isMissingNode() && !tr.isNull()) {
            String id = tr.path("id").asText("");
            String siteName = tr.path("site").asText("");
            if (!id.isBlank() && "youtube".equalsIgnoreCase(siteName)) trailer = "https://www.youtube.com/watch?v=" + id;
        }
        JsonNode airing = n.path("nextAiringEpisode");
        UserListEntry listEntry = override != null ? override : parseListEntry(n.path("mediaListEntry"));

        List<StreamingServiceLink> streamingServices = new ArrayList<>();
        JsonNode externalLinks = n.path("externalLinks");
        if (externalLinks.isArray()) {
            for (JsonNode link : externalLinks) {
                if (link.path("isDisabled").asBoolean(false)) continue;
                String typeText = link.path("type").asText("");
                if (!typeText.isBlank() && !"STREAMING".equalsIgnoreCase(typeText)) continue;
                URI uri = uriOrNull(link.path("url").asText(""));
                if (uri == null) continue;
                streamingServices.add(new StreamingServiceLink(
                        link.path("site").asText("Streaming"),
                        uri,
                        link.path("language").asText(""),
                        link.path("notes").asText(""),
                        link.path("icon").asText("")
                ));
            }
        }

        List<StreamingEpisodeLink> streamingEpisodes = new ArrayList<>();
        JsonNode episodeLinks = n.path("streamingEpisodes");
        if (episodeLinks.isArray()) {
            for (JsonNode episode : episodeLinks) {
                URI uri = uriOrNull(episode.path("url").asText(""));
                if (uri == null) continue;
                streamingEpisodes.add(new StreamingEpisodeLink(
                        episode.path("title").asText("Episode"),
                        episode.path("site").asText("Streaming"),
                        uri,
                        episode.path("thumbnail").asText("")
                ));
            }
        }

        return new AniMedia(
                n.path("id").asInt(), type, display, english, romaji, nativeTitle,
                strings(n.path("synonyms")), n.path("coverImage").path("extraLarge").asText(n.path("coverImage").path("large").asText("")),
                n.path("bannerImage").asText(""), cleanDescription(n.path("description").asText("")), n.path("format").asText(""),
                n.path("status").asText(""), n.path("season").asText(""), nullableInt(n, "seasonYear"), nullableInt(n, "episodes"),
                nullableInt(n, "chapters"), nullableInt(n, "duration"), nullableInt(n, "averageScore"), nullableInt(n, "meanScore"),
                nullableInt(n, "popularity"), nullableInt(n, "favourites"), nullableInt(n, "trending"), strings(n.path("genres")), tags,
                studios, n.path("source").asText(""), fuzzyDate(n.path("startDate")), fuzzyDate(n.path("endDate")),
                airing.path("episode").isNumber() ? airing.path("episode").asInt() : null,
                airing.path("airingAt").isNumber() ? airing.path("airingAt").asLong() : null,
                trailer, site.isBlank() ? null : URI.create(site), streamingServices, streamingEpisodes, listEntry
        );
    }

    private UserListEntry parseListEntry(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull() || !n.isObject()) return UserListEntry.empty();
        String statusText = n.path("status").asText("");
        MediaListStatus status = null;
        if (!statusText.isBlank()) {
            try { status = MediaListStatus.valueOf(statusText); } catch (Exception ignored) {}
        }
        List<String> custom = new ArrayList<>();
        JsonNode lists = n.path("customLists");
        if (lists.isObject()) lists.fields().forEachRemaining(e -> { if (e.getValue().asBoolean(false)) custom.add(e.getKey()); });
        return new UserListEntry(
                n.path("id").isNumber() ? n.path("id").asInt() : null, status, n.path("progress").asInt(0),
                n.path("score").asDouble(0), n.path("repeat").asInt(0), n.path("priority").asInt(0),
                n.path("private").asBoolean(false), n.path("notes").asText(""), custom,
                fuzzyDate(n.path("startedAt")), fuzzyDate(n.path("completedAt"))
        );
    }

    private static String mediaFields(boolean detail) {
        String common = """
                id type siteUrl bannerImage description(asHtml:false) format status season seasonYear episodes chapters duration
                averageScore meanScore popularity favourites trending source
                title { romaji english native }
                synonyms
                coverImage { extraLarge large color }
                genres
                tags { name rank isMediaSpoiler }
                studios(isMain:true) { nodes { name } }
                startDate { year month day }
                endDate { year month day }
                nextAiringEpisode { episode airingAt }
                trailer { id site thumbnail }
                """;
        if (!detail) return common;
        return common + """
                externalLinks { site url type language icon notes isDisabled }
                streamingEpisodes { title thumbnail url site }
                mediaListEntry {
                  id status score progress repeat priority private notes customLists
                  startedAt { year month day }
                  completedAt { year month day }
                }
                """;
    }

    private static String first(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return "";
    }

    private static List<String> strings(JsonNode n) {
        List<String> out = new ArrayList<>();
        if (n.isArray()) n.forEach(x -> { if (!x.asText("").isBlank()) out.add(x.asText()); });
        return out;
    }

    private static Integer nullableInt(JsonNode n, String field) {
        return n.path(field).isNumber() ? n.path(field).asInt() : null;
    }

    private static LocalDate fuzzyDate(JsonNode n) {
        if (n == null || !n.isObject() || !n.path("year").isNumber()) return null;
        int y = n.path("year").asInt();
        int m = n.path("month").asInt(1);
        int d = n.path("day").asInt(1);
        try { return LocalDate.of(y, Math.max(1,m), Math.max(1,d)); } catch (Exception ignored) { return null; }
    }

    private static URI uriOrNull(String value) {
        if (value == null || value.isBlank()) return null;
        try { return URI.create(value); } catch (Exception ignored) { return null; }
    }

    private static String cleanDescription(String value) {
        if (value == null || value.isBlank()) return "No description available.";
        return value.replace("<br>", "\n").replace("<br/>", "\n").replace("<br />", "\n")
                .replaceAll("<[^>]+>", "").replace("&amp;", "&").replace("&quot;", "\"").replace("&#039;", "'").trim();
    }
}
