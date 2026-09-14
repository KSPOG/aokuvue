package app.kspani.ui;

import app.kspani.app.AppContext;
import app.kspani.domain.AniMedia;
import app.kspani.domain.MediaType;
import app.kspani.source.EverythingMoeMangaDirectory;
import app.kspani.source.ProviderSite;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Adds adult discovery and Manga Reading source discovery without coupling source logic to MainWindow. */
public final class CatalogUiEnhancer {
    private static final String HENTAI_TILE_ID = "aokuvue-hentai-category";
    private static final String MANGA_SOURCE_ID = "aokuvue-manga-source-card";
    private static final String MANGA_SETTINGS_ID = "aokuvue-manga-directory-settings";

    private final MainWindow root;
    private final AppContext app;
    private final EverythingMoeMangaDirectory mangaDirectory = new EverythingMoeMangaDirectory();
    private StackPane contentHost;
    private volatile List<AniMedia> adultAnime = List.of();

    private CatalogUiEnhancer(MainWindow root, AppContext app) {
        this.root = root;
        this.app = app;
    }

    public static void install(MainWindow root, AppContext app) {
        if (root == null || app == null) return;
        CatalogUiEnhancer enhancer = new CatalogUiEnhancer(root, app);
        Platform.runLater(enhancer::attach);
    }

    private void attach() {
        contentHost = findContentHost(root);
        if (contentHost == null) return;
        contentHost.getChildren().addListener((ListChangeListener<Node>) change -> Platform.runLater(this::enhanceCurrentView));
        mangaDirectory.refresh().whenComplete((ignored, error) -> Platform.runLater(this::enhanceCurrentView));
        if (app.config().getBoolean("content.includeAdult", true)) loadAdultAnime();
        enhanceCurrentView();
    }

    private void loadAdultAnime() {
        String query = """
                query($perPage: Int) {
                  Page(page: 1, perPage: $perPage) {
                    media(type: ANIME, isAdult: true, sort: POPULARITY_DESC) { id }
                  }
                }
                """;
        app.anilist().execute(query, Map.of("perPage", 18)).thenCompose(rootNode -> {
            List<CompletableFuture<AniMedia>> requests = new ArrayList<>();
            rootNode.path("data").path("Page").path("media").forEach(node -> {
                if (node.path("id").isInt()) {
                    requests.add(app.anilist().details(node.path("id").asInt(), MediaType.ANIME)
                            .exceptionally(error -> null));
                }
            });
            return CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new))
                    .thenApply(ignored -> requests.stream().map(CompletableFuture::join).filter(java.util.Objects::nonNull).toList());
        }).whenComplete((items, error) -> Platform.runLater(() -> {
            if (error == null && items != null) adultAnime = List.copyOf(items);
            enhanceCurrentView();
        }));
    }

    private void enhanceCurrentView() {
        if (contentHost == null || contentHost.getChildren().isEmpty()) return;
        Node view = contentHost.getChildren().get(0);
        addHentaiCategory(view);
        addMangaDetailsSources(view);
        addMangaSettingsSources(view);
    }

    private void addHentaiCategory(Node view) {
        if (adultAnime.isEmpty() || findById(view, HENTAI_TILE_ID) != null) return;
        ScrollPane shelf = findScrollByStyle(view, "category-shelf");
        if (shelf == null || !(shelf.getContent() instanceof HBox categories)) return;

        AniMedia artMedia = adultAnime.get(0);
        ImageView art = new ImageView();
        art.setFitWidth(190); art.setFitHeight(78); art.setPreserveRatio(false); art.setSmooth(true);
        String artUrl = artMedia.bannerImage() == null || artMedia.bannerImage().isBlank() ? artMedia.coverImage() : artMedia.bannerImage();
        if (artUrl != null && !artUrl.isBlank()) art.setImage(new Image(artUrl, 190, 78, false, true, true));
        StackPane shade = new StackPane(); shade.getStyleClass().add("category-shade");
        Label title = new Label("Hentai"); title.getStyleClass().add("category-title");
        Label caption = new Label("18+ STORIES"); caption.getStyleClass().add("category-caption");
        VBox copy = new VBox(2, title, caption); copy.setAlignment(Pos.BOTTOM_CENTER);
        StackPane tile = new StackPane(art, shade, copy);
        tile.setId(HENTAI_TILE_ID); tile.setPrefSize(190, 78); tile.getStyleClass().add("category-tile");
        StackPane.setAlignment(copy, Pos.BOTTOM_CENTER); StackPane.setMargin(copy, new Insets(0, 8, 9, 8));
        tile.setOnMouseClicked(event -> invokeShowCollection("Hentai", adultAnime));
        categories.getChildren().add(tile);
    }

    private void addMangaDetailsSources(Node view) {
        AniMedia media = currentMedia();
        if (media == null || media.type() != MediaType.MANGA || findById(view, MANGA_SOURCE_ID) != null) return;
        VBox about = findMangaAboutContainer(view);
        if (about == null) return;
        VBox card = mangaSourceCard(media);
        card.setId(MANGA_SOURCE_ID);
        about.getChildren().add(0, card);
    }

    private VBox mangaSourceCard(AniMedia media) {
        Label kicker = new Label("READ IN AOKUVUE"); kicker.getStyleClass().add("section-kicker");
        Label title = new Label("Manga sources"); title.getStyleClass().add("section-title");
        Label note = new Label("EverythingMoe Manga Reading sources. Sites open inside AOKUVUE's embedded browser; chapter availability is controlled by the selected provider.");
        note.setWrapText(true); note.getStyleClass().add("source-status");
        ComboBox<ProviderSite> sources = new ComboBox<>(FXCollections.observableArrayList(mangaDirectory.latestSnapshot()));
        sources.setPrefWidth(320); if (!sources.getItems().isEmpty()) sources.getSelectionModel().selectFirst();
        Button open = new Button("Read in AOKUVUE"); open.getStyleClass().add("primary-button");
        open.disableProperty().bind(sources.valueProperty().isNull());
        open.setOnAction(e -> { ProviderSite source = sources.getValue(); if (source != null) invokeProviderBrowser(media, source); });
        Button refresh = new Button("Refresh sources"); refresh.getStyleClass().add("secondary-button");
        refresh.setOnAction(e -> {
            refresh.setDisable(true);
            mangaDirectory.refresh().whenComplete((sites, error) -> Platform.runLater(() -> {
                refresh.setDisable(false);
                sources.setItems(FXCollections.observableArrayList(sites == null ? mangaDirectory.latestSnapshot() : sites));
                if (!sources.getItems().isEmpty()) sources.getSelectionModel().selectFirst();
            }));
        });
        FlowPane controls = new FlowPane(8, 8, sources, open, refresh); controls.setAlignment(Pos.CENTER_LEFT);
        VBox card = new VBox(10, kicker, title, note, controls); card.getStyleClass().add("info-card"); card.setPadding(new Insets(18));
        return card;
    }

    private void addMangaSettingsSources(Node view) {
        if (findById(view, MANGA_SETTINGS_ID) != null) return;
        TabPane tabs = findTabPaneByStyle(view, "settings-tabs");
        if (tabs == null) return;
        Tab sourcesTab = tabs.getTabs().stream().filter(tab -> "Sources".equalsIgnoreCase(tab.getText())).findFirst().orElse(null);
        if (sourcesTab == null || !(sourcesTab.getContent() instanceof VBox sourcesBox)) return;

        Label summary = new Label(); summary.getStyleClass().add("source-status"); summary.setWrapText(true);
        FlowPane providers = new FlowPane(7, 7); providers.setAlignment(Pos.CENTER_LEFT);
        Button refresh = new Button("Refresh manga directory"); refresh.getStyleClass().add("secondary-button");
        VBox card = new VBox(11, new Label("AOKUVUE / EVERYTHINGMOE MANGA SOURCES"), new Label("Manga Reading directory"), summary, providers, refresh);
        card.setId(MANGA_SETTINGS_ID); card.getStyleClass().add("settings-card"); card.setPadding(new Insets(18));
        ((Label) card.getChildren().get(0)).getStyleClass().add("section-kicker");
        ((Label) card.getChildren().get(1)).getStyleClass().add("section-title");
        Runnable render = () -> {
            List<ProviderSite> sites = mangaDirectory.latestSnapshot();
            summary.setText(sites.size() + " manga source entries" + (sites.stream().anyMatch(ProviderSite::fromLiveDirectory) ? " · live EverythingMoe snapshot" : " · bundled fallback"));
            providers.getChildren().clear();
            sites.stream().limit(30).forEach(site -> {
                Label chip = new Label("#" + site.rank() + "  " + site.name() + (site.multiSource() ? " · MULT" : ""));
                chip.getStyleClass().add("provider-chip"); providers.getChildren().add(chip);
            });
        };
        refresh.setOnAction(e -> {
            refresh.setDisable(true);
            mangaDirectory.refresh().whenComplete((ignored, error) -> Platform.runLater(() -> { refresh.setDisable(false); render.run(); }));
        });
        render.run();
        sourcesBox.getChildren().add(card);
    }

    private void invokeShowCollection(String title, List<AniMedia> items) {
        try {
            Method method = MainWindow.class.getDeclaredMethod("showCollection", String.class, List.class);
            method.setAccessible(true); method.invoke(root, title, items);
        } catch (Exception error) {
            System.err.println("[Aokuvue][Catalog] Unable to open category: " + error.getMessage());
        }
    }

    private void invokeProviderBrowser(AniMedia media, ProviderSite site) {
        try {
            Method method = MainWindow.class.getDeclaredMethod("showEverythingMoePlayer", AniMedia.class, ProviderSite.class);
            method.setAccessible(true); method.invoke(root, media, site);
        } catch (Exception error) {
            System.err.println("[Aokuvue][Catalog] Unable to open manga source: " + error.getMessage());
        }
    }

    private AniMedia currentMedia() {
        try {
            Field field = MainWindow.class.getDeclaredField("currentMedia"); field.setAccessible(true);
            return (AniMedia) field.get(root);
        } catch (Exception ignored) { return null; }
    }

    private static StackPane findContentHost(Parent parent) {
        if (parent instanceof StackPane stack && stack.getStyleClass().contains("content-host")) return stack;
        for (Node child : parent.getChildrenUnmodifiable()) if (child instanceof Parent nested) {
            StackPane found = findContentHost(nested); if (found != null) return found;
        }
        return null;
    }

    private static VBox findMangaAboutContainer(Node node) {
        if (node instanceof VBox box) {
            boolean hasInfoCard = box.getChildren().stream().anyMatch(child -> child.getStyleClass().contains("info-card"));
            if (hasInfoCard && !box.getStyleClass().contains("page-body")) return box;
        }
        if (node instanceof ScrollPane scroll && scroll.getContent() != null) return findMangaAboutContainer(scroll.getContent());
        if (node instanceof Parent parent) for (Node child : parent.getChildrenUnmodifiable()) {
            VBox found = findMangaAboutContainer(child); if (found != null) return found;
        }
        return null;
    }

    private static ScrollPane findScrollByStyle(Node node, String style) {
        if (node instanceof ScrollPane scroll && scroll.getStyleClass().contains(style)) return scroll;
        if (node instanceof ScrollPane scroll && scroll.getContent() != null) { ScrollPane found = findScrollByStyle(scroll.getContent(), style); if (found != null) return found; }
        if (node instanceof Parent parent) for (Node child : parent.getChildrenUnmodifiable()) { ScrollPane found = findScrollByStyle(child, style); if (found != null) return found; }
        return null;
    }

    private static TabPane findTabPaneByStyle(Node node, String style) {
        if (node instanceof TabPane tabs && tabs.getStyleClass().contains(style)) return tabs;
        if (node instanceof ScrollPane scroll && scroll.getContent() != null) { TabPane found = findTabPaneByStyle(scroll.getContent(), style); if (found != null) return found; }
        if (node instanceof Parent parent) for (Node child : parent.getChildrenUnmodifiable()) { TabPane found = findTabPaneByStyle(child, style); if (found != null) return found; }
        return null;
    }

    private static Node findById(Node node, String id) {
        if (id.equals(node.getId())) return node;
        if (node instanceof ScrollPane scroll && scroll.getContent() != null) { Node found = findById(scroll.getContent(), id); if (found != null) return found; }
        if (node instanceof Parent parent) for (Node child : parent.getChildrenUnmodifiable()) { Node found = findById(child, id); if (found != null) return found; }
        if (node instanceof TabPane tabs) for (Tab tab : tabs.getTabs()) if (tab.getContent() != null) { Node found = findById(tab.getContent(), id); if (found != null) return found; }
        return null;
    }
}
