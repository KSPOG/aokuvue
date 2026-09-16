package app.kspani.ui;

import app.kspani.app.AppContext;
import app.kspani.domain.AniMedia;
import app.kspani.domain.MediaType;
import app.kspani.player.PlaybackProgressRepository;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Turns the legacy Library sidebar shortcut into a real local Aokuvue library.
 *
 * Library is backed by the playback_progress database, while My List remains the separate AniList
 * list surface. Metadata is reused from already-loaded AniList models and lazily resolved when a
 * locally played title is not currently present in one of MainWindow's in-memory collections.
 */
public final class LibraryUiEnhancer {
    private static final String LIBRARY_VIEW_ID = "aokuvue-local-library-view";
    private static final int LIBRARY_LIMIT = 60;
    private static final List<String> MEDIA_FIELDS = List.of(
            "trendingAnime", "popularAnime", "adultAnime", "trendingManga", "animeList", "mangaList"
    );

    private final MainWindow root;
    private final AppContext app;
    private final Map<Integer, AniMedia> mediaCache = new LinkedHashMap<>();
    private StackPane contentHost;
    private Button libraryButton;
    private EventHandler<ActionEvent> legacyLibraryAction;
    private int loadGeneration;

    private LibraryUiEnhancer(MainWindow root, AppContext app) {
        this.root = root;
        this.app = app;
    }

    public static void install(MainWindow root, AppContext app) {
        if (root == null || app == null) return;
        LibraryUiEnhancer enhancer = new LibraryUiEnhancer(root, app);
        Platform.runLater(enhancer::attach);
    }

    private void attach() {
        contentHost = findByStyle(root, "content-host", StackPane.class);
        libraryButton = findSidebarButton(root, "Library");
        if (contentHost == null || libraryButton == null) return;

        legacyLibraryAction = libraryButton.getOnAction();
        libraryButton.setOnAction(event -> {
            // Preserve MainWindow's normal page transition semantics (including stopping a player),
            // then replace the legacy My List result with the local library surface.
            if (legacyLibraryAction != null) legacyLibraryAction.handle(event);
            Platform.runLater(this::showLibrary);
        });
    }

    private void showLibrary() {
        int generation = ++loadGeneration;
        selectLibraryButton();

        List<PlaybackProgressRepository.ContinueEntry> entries;
        try {
            entries = app.playbackProgress().recent(LIBRARY_LIMIT);
        } catch (RuntimeException error) {
            replaceContent(errorView(error.getMessage()));
            return;
        }

        seedMediaCache();
        render(entries, generation, false);

        List<Integer> missing = entries.stream()
                .map(PlaybackProgressRepository.ContinueEntry::mediaId)
                .distinct()
                .filter(id -> !mediaCache.containsKey(id))
                .toList();
        if (missing.isEmpty()) return;

        List<CompletableFuture<Void>> loads = new ArrayList<>();
        for (Integer id : missing) {
            loads.add(app.anilist().details(id, MediaType.ANIME)
                    .handle((media, error) -> {
                        if (media != null) {
                            synchronized (mediaCache) { mediaCache.put(media.id(), media); }
                        }
                        return null;
                    }));
        }
        CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new))
                .whenComplete((ignored, error) -> Platform.runLater(() -> {
                    if (generation != loadGeneration || !isLibrarySelected()) return;
                    render(entries, generation, true);
                }));
    }

    private void render(List<PlaybackProgressRepository.ContinueEntry> entries, int generation, boolean metadataLoaded) {
        if (generation != loadGeneration) return;

        Label title = new Label("Library");
        title.getStyleClass().add("browse-title");
        Label nativeLine = new Label("あなたの物語");
        nativeLine.getStyleClass().add("browse-native");
        Label subtitle = new Label("Your local Aokuvue viewing library · generated from actual playback activity");
        subtitle.getStyleClass().add("browse-subtitle");
        VBox heading = new VBox(2, title, nativeLine, subtitle);
        heading.setPadding(new Insets(24, AokuvueTheme.PAGE_GUTTER, 10, AokuvueTheme.PAGE_GUTTER));

        Button refresh = new Button("Refresh library");
        refresh.getStyleClass().add("secondary-button");
        refresh.setOnAction(event -> showLibrary());
        Label count = new Label(entries.size() + (entries.size() == 1 ? " title" : " titles"));
        count.getStyleClass().add("search-count");
        Region toolbarSpacer = new Region();
        HBox.setHgrow(toolbarSpacer, Priority.ALWAYS);
        HBox toolbar = new HBox(10, count, toolbarSpacer, refresh);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(0, AokuvueTheme.PAGE_GUTTER, 6, AokuvueTheme.PAGE_GUTTER));

        VBox body = new VBox(14, heading, toolbar);
        body.setId(LIBRARY_VIEW_ID);
        body.getStyleClass().add("page-body");

        if (entries.isEmpty()) {
            VBox empty = new VBox(9,
                    sectionTitle("Your library is empty"),
                    muted("Play an episode in Aokuvue and it will appear here automatically. My List remains your separate AniList account list."));
            empty.setPadding(new Insets(28));
            empty.getStyleClass().add("empty-state");
            VBox.setMargin(empty, new Insets(10, AokuvueTheme.PAGE_GUTTER, 32, AokuvueTheme.PAGE_GUTTER));
            body.getChildren().add(empty);
            replaceContent(scroll(body));
            return;
        }

        List<PlaybackProgressRepository.ContinueEntry> watching = entries.stream().filter(entry -> !entry.watched()).toList();
        List<PlaybackProgressRepository.ContinueEntry> watched = entries.stream().filter(PlaybackProgressRepository.ContinueEntry::watched).toList();

        if (!watching.isEmpty()) body.getChildren().add(librarySection("Continue Watching", watching));
        if (!watched.isEmpty()) body.getChildren().add(librarySection("Recently Watched", watched));

        long unresolved = entries.stream().filter(entry -> !mediaCache.containsKey(entry.mediaId())).count();
        if (unresolved > 0 && !metadataLoaded) {
            ProgressIndicator spinner = new ProgressIndicator();
            spinner.setPrefSize(20, 20);
            Label loading = muted("Resolving " + unresolved + " title" + (unresolved == 1 ? "" : "s") + " from AniList…");
            HBox state = new HBox(9, spinner, loading);
            state.setAlignment(Pos.CENTER_LEFT);
            state.setPadding(new Insets(0, AokuvueTheme.PAGE_GUTTER, 30, AokuvueTheme.PAGE_GUTTER));
            body.getChildren().add(state);
        } else if (unresolved > 0) {
            Label note = muted(unresolved + " local entr" + (unresolved == 1 ? "y could" : "ies could") + " not currently be resolved from AniList.");
            VBox.setMargin(note, new Insets(0, AokuvueTheme.PAGE_GUTTER, 30, AokuvueTheme.PAGE_GUTTER));
            body.getChildren().add(note);
        }

        replaceContent(scroll(body));
    }

    private VBox librarySection(String name, List<PlaybackProgressRepository.ContinueEntry> entries) {
        Label title = sectionTitle(name);
        FlowPane cards = new FlowPane(16, 18);
        cards.setAlignment(Pos.TOP_LEFT);

        for (PlaybackProgressRepository.ContinueEntry entry : entries) {
            AniMedia media = mediaCache.get(entry.mediaId());
            if (media == null) continue;
            String meta = progressMeta(entry);
            cards.getChildren().add(new MediaCard(media, this::openDetails, meta));
        }

        VBox section = new VBox(9, title, cards);
        section.setPadding(new Insets(8, AokuvueTheme.PAGE_GUTTER, 18, AokuvueTheme.PAGE_GUTTER));
        return section;
    }

    private static String progressMeta(PlaybackProgressRepository.ContinueEntry entry) {
        String episode = "Episode " + entry.episodeNumber();
        if (entry.watched()) return episode + " · Watched";
        if (entry.durationMs() > 0) {
            int percent = (int) Math.round(Math.min(1.0, Math.max(0.0,
                    (double) entry.positionMs() / entry.durationMs())) * 100.0);
            return episode + " · " + percent + "%";
        }
        return episode + " · In progress";
    }

    private void seedMediaCache() {
        for (String fieldName : MEDIA_FIELDS) {
            try {
                Field field = MainWindow.class.getDeclaredField(fieldName);
                field.setAccessible(true);
                Object value = field.get(root);
                if (!(value instanceof List<?> list)) continue;
                for (Object item : list) {
                    if (item instanceof AniMedia media) mediaCache.put(media.id(), media);
                }
            } catch (ReflectiveOperationException ignored) {
                // A missing cache field should not break the local library; AniList resolution is the fallback.
            }
        }
    }

    private void openDetails(AniMedia media) {
        try {
            Method method = MainWindow.class.getDeclaredMethod("openDetails", AniMedia.class);
            method.setAccessible(true);
            method.invoke(root, media);
        } catch (ReflectiveOperationException error) {
            System.err.println("[Aokuvue][Library] Unable to open details: " + error.getMessage());
        }
    }

    private void selectLibraryButton() {
        for (Button button : findButtons(root)) button.getStyleClass().remove("selected");
        if (!libraryButton.getStyleClass().contains("selected")) libraryButton.getStyleClass().add("selected");
    }

    private boolean isLibrarySelected() {
        return libraryButton != null && libraryButton.getStyleClass().contains("selected")
                && contentHost != null && !contentHost.getChildren().isEmpty()
                && LIBRARY_VIEW_ID.equals(contentHost.getChildren().get(0).getId());
    }

    private void replaceContent(Node node) {
        if (contentHost != null) contentHost.getChildren().setAll(node);
    }

    private static ScrollPane scroll(Node content) {
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("page-scroll");
        scroll.setId(LIBRARY_VIEW_ID);
        return scroll;
    }

    private static VBox errorView(String message) {
        Label title = sectionTitle("Library unavailable");
        Label detail = muted(message == null || message.isBlank() ? "Unable to read local playback history." : message);
        VBox box = new VBox(10, title, detail);
        box.setPadding(new Insets(30));
        box.getStyleClass().add("empty-state");
        box.setId(LIBRARY_VIEW_ID);
        return box;
    }

    private static Label sectionTitle(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("section-title");
        return label;
    }

    private static Label muted(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.getStyleClass().add("source-status");
        return label;
    }

    private static Button findSidebarButton(Parent root, String label) {
        for (Button button : findButtons(root)) {
            String text = button.getText() == null ? "" : button.getText();
            if (text.contains(label)) return button;
        }
        return null;
    }

    private static List<Button> findButtons(Parent root) {
        List<Button> buttons = new ArrayList<>();
        collectButtons(root, buttons);
        return buttons;
    }

    private static void collectButtons(Node node, List<Button> target) {
        if (node instanceof Button button && button.getStyleClass().contains("nav-tab")) target.add(button);
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) collectButtons(child, target);
        }
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
