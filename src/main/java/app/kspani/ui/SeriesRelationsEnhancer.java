package app.kspani.ui;

import app.kspani.app.AppContext;
import app.kspani.domain.AniMedia;
import app.kspani.domain.AnimeSeasonRef;
import app.kspani.domain.MediaType;
import com.fasterxml.jackson.databind.JsonNode;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Adds direct PREQUEL / SEQUEL navigation cards to the lower-right corner of every anime detail hero.
 * Relation data comes from AniList and is never title-specific.
 */
public final class SeriesRelationsEnhancer {
    private static final String ENHANCED = SeriesRelationsEnhancer.class.getName() + ".enhanced";
    private static final String QUERY = """
            query($id: Int!) {
              Media(id: $id, type: ANIME) {
                relations {
                  edges {
                    relationType
                    node {
                      id
                      type
                      format
                      season
                      seasonYear
                      episodes
                      title { english romaji native }
                      coverImage { extraLarge large }
                      startDate { year month day }
                    }
                  }
                }
              }
            }
            """;

    private final MainWindow root;
    private final AppContext app;
    private StackPane contentHost;
    private int generation;

    private record RelationRef(
            String relation,
            int mediaId,
            String title,
            String coverUrl,
            String format,
            String season,
            Integer seasonYear,
            Integer episodes,
            LocalDate startDate
    ) {}

    private SeriesRelationsEnhancer(MainWindow root, AppContext app) {
        this.root = root;
        this.app = app;
    }

    public static void install(MainWindow root, AppContext app) {
        if (root == null || app == null) return;
        SeriesRelationsEnhancer enhancer = new SeriesRelationsEnhancer(root, app);
        Platform.runLater(enhancer::attach);
    }

    private void attach() {
        contentHost = findByStyle(root, "content-host", StackPane.class);
        if (contentHost == null) return;
        contentHost.getChildren().addListener((ListChangeListener<Node>) change -> Platform.runLater(this::enhanceCurrentView));
        enhanceCurrentView();
    }

    private void enhanceCurrentView() {
        if (contentHost == null || contentHost.getChildren().isEmpty()) return;
        Node view = contentHost.getChildren().get(0);
        StackPane hero = findByStyle(view, "detail-hero", StackPane.class);
        if (hero == null || Boolean.TRUE.equals(hero.getProperties().get(ENHANCED))) return;

        AniMedia media = currentMedia();
        if (media == null || media.type() != MediaType.ANIME) return;
        hero.getProperties().put(ENHANCED, Boolean.TRUE);
        int requestGeneration = ++generation;

        loadRelations(media.id()).whenComplete((relations, error) -> Platform.runLater(() -> {
            if (requestGeneration != generation || error != null || relations == null || relations.isEmpty()) return;
            AniMedia current = currentMedia();
            if (current == null || current.id() != media.id() || hero.getScene() == null) return;
            hero.getChildren().add(relationPanel(relations));
        }));
    }

    private CompletableFuture<List<RelationRef>> loadRelations(int mediaId) {
        return app.anilist().execute(QUERY, Map.of("id", mediaId)).thenApply(root -> {
            JsonNode edges = root.path("data").path("Media").path("relations").path("edges");
            if (!edges.isArray()) return List.of();

            // One nearest/direct card per relation direction keeps the hero compact and predictable.
            Map<String, RelationRef> nearest = new LinkedHashMap<>();
            for (JsonNode edge : edges) {
                String relation = edge.path("relationType").asText("");
                if (!"PREQUEL".equals(relation) && !"SEQUEL".equals(relation)) continue;
                JsonNode node = edge.path("node");
                if (!"ANIME".equalsIgnoreCase(node.path("type").asText(""))) continue;
                int id = node.path("id").asInt(0);
                if (id <= 0) continue;

                JsonNode titleNode = node.path("title");
                String title = first(
                        titleNode.path("english").asText(""),
                        titleNode.path("romaji").asText(""),
                        titleNode.path("native").asText(""),
                        "Related series"
                );
                String cover = first(
                        node.path("coverImage").path("extraLarge").asText(""),
                        node.path("coverImage").path("large").asText("")
                );
                RelationRef candidate = new RelationRef(
                        relation,
                        id,
                        title,
                        cover,
                        node.path("format").asText(""),
                        node.path("season").asText(""),
                        nullableInt(node, "seasonYear"),
                        nullableInt(node, "episodes"),
                        fuzzyDate(node.path("startDate"))
                );

                RelationRef previous = nearest.get(relation);
                if (previous == null || isCloser(candidate, previous, relation)) nearest.put(relation, candidate);
            }

            List<RelationRef> result = new ArrayList<>();
            if (nearest.containsKey("PREQUEL")) result.add(nearest.get("PREQUEL"));
            if (nearest.containsKey("SEQUEL")) result.add(nearest.get("SEQUEL"));
            return List.copyOf(result);
        });
    }

    private static boolean isCloser(RelationRef candidate, RelationRef previous, String relation) {
        if (candidate.startDate() == null) return false;
        if (previous.startDate() == null) return true;
        return "PREQUEL".equals(relation)
                ? candidate.startDate().isAfter(previous.startDate())
                : candidate.startDate().isBefore(previous.startDate());
    }

    private Node relationPanel(List<RelationRef> relations) {
        Label eyebrow = new Label("SERIES TIMELINE");
        eyebrow.getStyleClass().add("section-kicker");

        HBox cards = new HBox(7);
        cards.setAlignment(Pos.CENTER_RIGHT);
        cards.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        for (RelationRef relation : relations) cards.getChildren().add(relationCard(relation));

        VBox panel = new VBox(5, eyebrow, cards);
        panel.setAlignment(Pos.BOTTOM_RIGHT);
        // StackPane stretches resizable children by default. Force this overlay to hug its content
        // instead of becoming a large dark rectangle across the hero.
        panel.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        panel.setPadding(new Insets(7));
        panel.setStyle(
                "-fx-background-color: rgba(8,8,14,.86);"
                        + "-fx-border-color: rgba(168,162,199,.28);"
                        + "-fx-border-radius: 8px;"
                        + "-fx-background-radius: 8px;"
        );
        StackPane.setAlignment(panel, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(panel, new Insets(0, 14, 14, 0));
        return panel;
    }

    private Node relationCard(RelationRef relation) {
        ImageView cover = new ImageView();
        cover.setFitWidth(46);
        cover.setFitHeight(66);
        cover.setPreserveRatio(false);
        cover.getStyleClass().add("poster-art");
        if (relation.coverUrl() != null && !relation.coverUrl().isBlank()) {
            try { cover.setImage(new Image(relation.coverUrl(), true)); }
            catch (RuntimeException ignored) {}
        }

        Label relationLabel = new Label(relation.relation());
        relationLabel.getStyleClass().add("section-kicker");

        Label title = new Label(relation.title());
        title.setWrapText(true);
        title.setMaxWidth(118);
        title.setMaxHeight(34);
        title.getStyleClass().add("poster-title");

        StringBuilder metaText = new StringBuilder();
        if (relation.seasonYear() != null) metaText.append(relation.seasonYear());
        if (relation.format() != null && !relation.format().isBlank()) {
            if (!metaText.isEmpty()) metaText.append(" · ");
            metaText.append(relation.format().replace('_', ' '));
        }
        if (relation.episodes() != null) {
            if (!metaText.isEmpty()) metaText.append(" · ");
            metaText.append(relation.episodes()).append(" eps");
        }
        Label meta = new Label(metaText.length() == 0 ? "Anime" : metaText.toString());
        meta.getStyleClass().add("poster-meta");

        VBox copy = new VBox(3, relationLabel, title, meta);
        copy.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(copy, Priority.ALWAYS);

        HBox card = new HBox(7, cover, copy);
        card.setAlignment(Pos.CENTER_LEFT);
        card.setPrefWidth(184);
        card.setMinWidth(184);
        card.setMaxWidth(184);
        card.setPadding(new Insets(5));
        card.getStyleClass().add("media-card");
        card.setCursor(Cursor.HAND);
        card.setOnMouseClicked(event -> openRelation(relation));
        return card;
    }

    private void openRelation(RelationRef relation) {
        try {
            AnimeSeasonRef ref = new AnimeSeasonRef(
                    relation.mediaId(), relation.title(), relation.format(), relation.season(),
                    relation.seasonYear(), relation.episodes(), relation.startDate());
            Method method = MainWindow.class.getDeclaredMethod("openSeason", AnimeSeasonRef.class);
            method.setAccessible(true);
            method.invoke(root, ref);
        } catch (ReflectiveOperationException error) {
            System.err.println("[Aokuvue][Relations] Unable to open related series: " + rootMessage(error));
        }
    }

    private AniMedia currentMedia() {
        try {
            Field field = MainWindow.class.getDeclaredField("currentMedia");
            field.setAccessible(true);
            Object value = field.get(root);
            return value instanceof AniMedia media ? media : null;
        } catch (ReflectiveOperationException error) {
            return null;
        }
    }

    private static Integer nullableInt(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asInt();
    }

    private static LocalDate fuzzyDate(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        int year = node.path("year").asInt(0);
        if (year <= 0) return null;
        int month = Math.max(1, node.path("month").asInt(1));
        int day = Math.max(1, node.path("day").asInt(1));
        try { return LocalDate.of(year, month, day); }
        catch (RuntimeException ignored) { return null; }
    }

    private static String first(String... values) {
        if (values == null) return "";
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }

    private static String rootMessage(Throwable error) {
        if (error == null) return "Unknown error";
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private static <T extends Node> T findByStyle(Node node, String styleClass, Class<T> type) {
        if (type.isInstance(node) && node.getStyleClass().contains(styleClass)) return type.cast(node);
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                T found = findByStyle(child, styleClass, type);
                if (found != null) return found;
            }
        }
        return null;
    }
}
