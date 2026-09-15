package app.kspani.ui;

import app.kspani.anilist.AniListDiscoveryService;
import app.kspani.anilist.AniListDiscoveryService.AiringRelease;
import app.kspani.anilist.AniListDiscoveryService.CatalogFilter;
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
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Enhances Aokuvue's catalog with AniList-backed discovery, calendar and genre navigation while
 * keeping Manga Reading source discovery decoupled from the large MainWindow class.
 */
public final class CatalogUiEnhancer {
    private static final int CATALOG_RENDER_BATCH_SIZE = 8;
    private static final String HENTAI_TILE_ID = "aokuvue-hentai-category";
    private static final String MANGA_SOURCE_ID = "aokuvue-manga-source-card";
    private static final String MANGA_SETTINGS_ID = "aokuvue-manga-directory-settings";
    private static final String DISCOVERY_VIEW_ID = "aokuvue-discovery-view";
    private static final String CALENDAR_VIEW_ID = "aokuvue-calendar-view";
    private static final String GENRES_VIEW_ID = "aokuvue-genres-view";

    private static final DateTimeFormatter CALENDAR_DAY = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy");
    private static final DateTimeFormatter CALENDAR_TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final MainWindow root;
    private final AppContext app;
    private final EverythingMoeMangaDirectory mangaDirectory = new EverythingMoeMangaDirectory();
    private final AniListDiscoveryService discovery;
    private final Map<String, Button> sidebarButtons = new LinkedHashMap<>();
    private StackPane contentHost;
    private volatile List<AniMedia> adultAnime = List.of();
    private boolean replacingView;

    private CatalogUiEnhancer(MainWindow root, AppContext app) {
        this.root = root;
        this.app = app;
        this.discovery = new AniListDiscoveryService(
                app.anilist(),
                app.config().getBoolean("content.includeAdult", true)
        );
    }

    public static void install(MainWindow root, AppContext app) {
        if (root == null || app == null) return;
        CatalogUiEnhancer enhancer = new CatalogUiEnhancer(root, app);
        Platform.runLater(enhancer::attach);
    }

    private void attach() {
        contentHost = findContentHost(root);
        if (contentHost == null) return;
        bindCatalogNavigation();
        contentHost.getChildren().addListener((ListChangeListener<Node>) change -> Platform.runLater(this::enhanceCurrentView));
        mangaDirectory.refresh().whenComplete((ignored, error) -> Platform.runLater(this::enhanceCurrentView));
        if (app.config().getBoolean("content.includeAdult", true)) loadAdultAnime();
        enhanceCurrentView();
    }

    private void bindCatalogNavigation() {
        bindSidebar("Explore", () -> showPagedCatalog(
                "Explore",
                "AniList anime discovery · pages load continuously as you browse",
                CatalogFilter.anime(),
                "ANIME",
                sidebarButtons.get("Explore")
        ));
        bindSidebar("Calendar", () -> showCalendar(sidebarButtons.get("Calendar")));
        bindSidebar("Hentai", () -> showPagedCatalog(
                "Hentai",
                "18+ AniList discovery · paged directly from AniList",
                new CatalogFilter(MediaType.ANIME, "POPULARITY_DESC", null, null, "Hentai", null, null, true),
                "HENTAI",
                sidebarButtons.get("Hentai")
        ));
        bindSidebar("Manga", () -> showPagedCatalog(
                "Manga",
                "AniList manga discovery · no fixed catalog ceiling",
                CatalogFilter.manga(),
                "MANGA",
                sidebarButtons.get("Manga")
        ));
        bindSidebar("Movies", () -> showPagedCatalog(
                "Movies",
                "Anime movies from AniList",
                new CatalogFilter(MediaType.ANIME, "POPULARITY_DESC", "MOVIE", null, null, null, null, false),
                "ANIME",
                sidebarButtons.get("Movies")
        ));
        bindSidebar("Originals", () -> showPagedCatalog(
                "Originals",
                "Original anime · populated directly from AniList",
                new CatalogFilter(MediaType.ANIME, "POPULARITY_DESC", null, "ORIGINAL", null, null, null, false),
                "ANIME",
                sidebarButtons.get("Originals")
        ));
        bindSidebar("Genres", () -> showGenres(sidebarButtons.get("Genres")));
        bindSidebar("Curated", () -> showPagedCatalog(
                "Curated",
                "High-scoring anime from AniList",
                new CatalogFilter(MediaType.ANIME, "SCORE_DESC", null, null, null, 79, null, false),
                "ANIME",
                sidebarButtons.get("Curated")
        ));
    }

    private void bindSidebar(String name, Runnable action) {
        Button button = findSidebarButton(root, name);
        if (button == null) return;
        sidebarButtons.put(name, button);
        button.setOnAction(event -> action.run());
    }

    private void loadAdultAnime() {
        app.anilist().browseAdultAnime("POPULARITY_DESC", 18).whenComplete((items, error) -> Platform.runLater(() -> {
            if (error == null && items != null) adultAnime = List.copyOf(items);
            enhanceCurrentView();
        }));
    }

    private void enhanceCurrentView() {
        if (replacingView || contentHost == null || contentHost.getChildren().isEmpty()) return;
        Node view = contentHost.getChildren().get(0);
        removeLegacyGenreDropdown(view);
        addHentaiCategory(view);
        addMangaDetailsSources(view);
        addMangaSettingsSources(view);
        upgradeLegacyCatalog(view);
    }

    /**
     * Old MainWindow paths (for example a Home shelf's "Explore all") still build the original
     * fixed-size browse view. Replace those views with the paged AniList version as soon as they appear.
     */
    private void upgradeLegacyCatalog(Node view) {
        if (isEnhancedCatalogView(view)) return;
        Label heading = findLabelByStyle(view, "browse-title");
        if (heading == null) return;
        String title = heading.getText() == null ? "" : heading.getText().trim();
        switch (title) {
            case "Anime" -> Platform.runLater(() -> showPagedCatalog(
                    "Explore", "AniList anime discovery · pages load continuously as you browse",
                    CatalogFilter.anime(), "ANIME", sidebarButtons.get("Explore")));
            case "Manga" -> Platform.runLater(() -> showPagedCatalog(
                    "Manga", "AniList manga discovery · no fixed catalog ceiling",
                    CatalogFilter.manga(), "MANGA", sidebarButtons.get("Manga")));
            case "Hentai" -> Platform.runLater(() -> showPagedCatalog(
                    "Hentai", "18+ AniList discovery · paged directly from AniList",
                    new CatalogFilter(MediaType.ANIME, "POPULARITY_DESC", null, null, "Hentai", null, null, true),
                    "HENTAI", sidebarButtons.get("Hentai")));
            case "Movies" -> Platform.runLater(() -> showPagedCatalog(
                    "Movies", "Anime movies from AniList",
                    new CatalogFilter(MediaType.ANIME, "POPULARITY_DESC", "MOVIE", null, null, null, null, false),
                    "ANIME", sidebarButtons.get("Movies")));
            case "Originals" -> Platform.runLater(() -> showPagedCatalog(
                    "Originals", "Original anime · populated directly from AniList",
                    new CatalogFilter(MediaType.ANIME, "POPULARITY_DESC", null, "ORIGINAL", null, null, null, false),
                    "ANIME", sidebarButtons.get("Originals")));
            case "Curated" -> Platform.runLater(() -> showPagedCatalog(
                    "Curated", "High-scoring anime from AniList",
                    new CatalogFilter(MediaType.ANIME, "SCORE_DESC", null, null, null, 79, null, false),
                    "ANIME", sidebarButtons.get("Curated")));
            default -> { }
        }
    }

    private void showPagedCatalog(String title, String subtitle, CatalogFilter baseFilter, String mainPage, Button activeButton) {
        if (contentHost == null) return;
        setMainPage(mainPage);
        selectSidebar(activeButton);

        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("browse-title");
        Label nativeLine = new Label("まだ見ぬ物語を");
        nativeLine.getStyleClass().add("browse-native");
        Label subtitleLabel = new Label(subtitle);
        subtitleLabel.getStyleClass().add("browse-subtitle");
        VBox headerCopy = new VBox(2, titleLabel, nativeLine, subtitleLabel);
        StackPane header = bannerHeader(headerCopy);
        header.getStyleClass().add("browse-header");

        TilePane grid = new TilePane();
        grid.setHgap(16);
        grid.setVgap(18);
        grid.setPrefTileWidth(190);
        grid.setAlignment(Pos.TOP_LEFT);

        Label count = new Label("Loading AniList…");
        count.getStyleClass().add("search-count");
        Button loadMore = new Button("Load more");
        loadMore.getStyleClass().add("secondary-button");
        ProgressIndicator progress = new ProgressIndicator();
        progress.setPrefSize(24, 24);
        HBox paging = new HBox(10, count, progress, loadMore);
        paging.setAlignment(Pos.CENTER_LEFT);

        HBox filterBar = new HBox(9);
        filterBar.setAlignment(Pos.CENTER_LEFT);
        filterBar.getStyleClass().add("browse-filter-bar");

        ComboBox<String> year = new ComboBox<>(FXCollections.observableArrayList(yearOptions()));
        year.setValue("Any year");
        ComboBox<String> sort = new ComboBox<>(FXCollections.observableArrayList("Popular", "Score", "Trending", "Newest"));
        sort.setValue(sortDisplay(baseFilter.sort()));
        sort.getStyleClass().add("browse-sort");

        CatalogPager pager = new CatalogPager(baseFilter, grid, count, progress, loadMore);

        if (baseFilter.format() == null) {
            HBox formats = formatFilters(baseFilter.type(), baseFilter.hentai(), pager);
            filterBar.getChildren().add(formats);
        }
        filterBar.getChildren().add(year);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        filterBar.getChildren().addAll(spacer, new Label("Sort"), sort);

        year.setOnAction(event -> {
            String value = year.getValue();
            Integer selected = value == null || value.equals("Any year") ? null : Integer.parseInt(value);
            pager.setYear(selected);
        });
        sort.setOnAction(event -> pager.setSort(sortValue(sort.getValue())));
        loadMore.setOnAction(event -> pager.loadNext());

        VBox body = new VBox();
        body.getChildren().add(header);
        // Explore is an editorial discovery surface, and genre results already have a fixed
        // context. Keep browse controls on the catalog pages where they are meaningful.
        boolean showBrowseControls = !"Explore".equals(title)
                && (baseFilter.genre() == null || baseFilter.hentai());
        if (showBrowseControls) body.getChildren().add(filterBar);
        body.getChildren().addAll(grid, paging);
        body.setId(DISCOVERY_VIEW_ID);
        body.getStyleClass().add("page-body");
        VBox.setMargin(grid, new Insets(18, AokuvueTheme.PAGE_GUTTER, 12, AokuvueTheme.PAGE_GUTTER));
        VBox.setMargin(paging, new Insets(0, AokuvueTheme.PAGE_GUTTER, 40, AokuvueTheme.PAGE_GUTTER));
        ScrollPane scroll = scroll(body);
        scroll.setId(DISCOVERY_VIEW_ID);
        scroll.addEventFilter(ScrollEvent.SCROLL, event -> {
            if (event.getDeltaY() >= 0) return;
            Platform.runLater(() -> {
                if (scroll.getVvalue() > 0.90) pager.loadNext();
            });
        });
        replaceContent(scroll);
        pager.loadNext();
    }

    private HBox formatFilters(MediaType type, boolean hentai, CatalogPager pager) {
        List<String> labels = type == MediaType.MANGA
                ? List.of("All", "Manga", "Novel", "One Shot")
                : List.of("All", "TV", "Movie", "OVA", "ONA", "Special");
        HBox filters = new HBox(8);
        for (String label : labels) {
            Button button = new Button(label);
            button.getStyleClass().add(label.equals("All") ? "filter-chip-selected" : "filter-chip");
            button.setOnAction(event -> {
                filters.getChildren().forEach(node -> {
                    node.getStyleClass().remove("filter-chip-selected");
                    if (!node.getStyleClass().contains("filter-chip")) node.getStyleClass().add("filter-chip");
                });
                button.getStyleClass().remove("filter-chip");
                button.getStyleClass().add("filter-chip-selected");
                String format = switch (label) {
                    case "TV" -> "TV";
                    case "Movie" -> "MOVIE";
                    case "OVA" -> "OVA";
                    case "ONA" -> "ONA";
                    case "Special" -> "SPECIAL";
                    case "Manga" -> "MANGA";
                    case "Novel" -> "NOVEL";
                    case "One Shot" -> "ONE_SHOT";
                    default -> null;
                };
                pager.setFormat(format);
            });
            filters.getChildren().add(button);
        }
        return filters;
    }

    private final class CatalogPager {
        private final CatalogFilter base;
        private final TilePane grid;
        private final Label count;
        private final ProgressIndicator progress;
        private final Button loadMore;
        private final Set<Integer> ids = new HashSet<>();
        private int page;
        private int generation;
        private int total;
        private boolean hasNext = true;
        private boolean loading;
        private String format;
        private String sort;
        private Integer year;

        private CatalogPager(CatalogFilter base, TilePane grid, Label count, ProgressIndicator progress, Button loadMore) {
            this.base = base;
            this.grid = grid;
            this.count = count;
            this.progress = progress;
            this.loadMore = loadMore;
            this.format = base.format();
            this.sort = base.sort();
            this.year = base.year();
        }

        private CatalogFilter filter() {
            return new CatalogFilter(
                    base.type(), sort, format, base.source(), base.genre(), base.minimumScore(), year, base.hentai()
            );
        }

        private void setFormat(String value) { format = value; reset(); }
        private void setSort(String value) { sort = value; reset(); }
        private void setYear(Integer value) { year = value; reset(); }

        private void reset() {
            generation++;
            page = 0;
            total = 0;
            hasNext = true;
            loading = false;
            ids.clear();
            grid.getChildren().clear();
            count.setText("Loading AniList…");
            loadNext();
        }

        private void loadNext() {
            if (loading || !hasNext) return;
            loading = true;
            progress.setVisible(true);
            loadMore.setDisable(true);
            int requested = page + 1;
            int token = generation;
            long startedAt = System.nanoTime();
            discovery.browse(filter(), requested).whenComplete((result, error) -> Platform.runLater(() -> {
                if (token != generation) return;
                if (error != null) {
                    loading = false;
                    progress.setVisible(false);
                    count.setText("AniList error: " + rootMessage(error));
                    loadMore.setDisable(false);
                    System.err.println("[Aokuvue][Catalog] " + base.type() + " page " + requested
                            + " failed: " + rootMessage(error));
                    return;
                }
                page = result.page();
                total = result.total();
                hasNext = result.hasNextPage();
                List<AniMedia> pending = new ArrayList<>();
                for (AniMedia media : result.items()) {
                    if (ids.add(media.id())) pending.add(media);
                }
                count.setText("Rendering " + pending.size() + " titles…");
                appendCards(pending, 0, token, requested, startedAt);
            }));
        }

        private void appendCards(List<AniMedia> items, int offset, int token, int requested, long startedAt) {
            if (token != generation) return;
            int end = Math.min(items.size(), offset + CATALOG_RENDER_BATCH_SIZE);
            for (int index = offset; index < end; index++) {
                AniMedia media = items.get(index);
                try {
                    grid.getChildren().add(new MediaCard(media, CatalogUiEnhancer.this::openDetails));
                } catch (RuntimeException error) {
                    System.err.println("[Aokuvue][Catalog] Skipped media " + media.id()
                            + " because its card could not be rendered: " + rootMessage(error));
                }
            }
            if (end < items.size()) {
                Platform.runLater(() -> appendCards(items, end, token, requested, startedAt));
                return;
            }
            finishLoad(requested, startedAt);
        }

        private void finishLoad(int requested, long startedAt) {
            loading = false;
            progress.setVisible(false);
            count.setText(ids.size() + (total > 0 ? " of " + total : "") + " titles loaded");
            loadMore.setText(hasNext ? "Load more" : "All titles loaded");
            loadMore.setDisable(!hasNext);
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
            System.out.println("[Aokuvue][Catalog] " + base.type() + " page " + requested
                    + " ready in " + elapsedMs + " ms; rendered=" + ids.size()
                    + ", hasNext=" + hasNext);
        }
    }

    private void showGenres(Button activeButton) {
        if (contentHost == null) return;
        setMainPage("ANIME");
        selectSidebar(activeButton);

        Label title = new Label("Genres");
        title.getStyleClass().add("browse-title");
        Label nativeLine = new Label("ジャンルから探す");
        nativeLine.getStyleClass().add("browse-native");
        Label subtitle = new Label("Browse AniList's complete genre collection");
        subtitle.getStyleClass().add("browse-subtitle");
        StackPane header = bannerHeader(new VBox(2, title, nativeLine, subtitle));
        header.getStyleClass().add("browse-header");

        TilePane cards = new TilePane();
        cards.setHgap(16);
        cards.setVgap(16);
        cards.setPrefTileWidth(286);
        cards.setPrefTileHeight(164);
        cards.setAlignment(Pos.TOP_LEFT);
        ProgressIndicator loading = new ProgressIndicator();
        VBox body = new VBox(18, header, loading, cards);
        body.setId(GENRES_VIEW_ID);
        body.getStyleClass().add("page-body");
        VBox.setMargin(loading, new Insets(20, AokuvueTheme.PAGE_GUTTER, 0, AokuvueTheme.PAGE_GUTTER));
        VBox.setMargin(cards, new Insets(0, AokuvueTheme.PAGE_GUTTER, 40, AokuvueTheme.PAGE_GUTTER));
        replaceContent(scroll(body));

        discovery.genres().thenCombine(
                discovery.browse(CatalogFilter.anime(), 1)
                        .exceptionally(error -> new AniListDiscoveryService.MediaPage(List.of(), 1, 0, false)),
                (genres, featured) -> Map.entry(genres, featured.items())
        ).whenComplete((result, error) -> Platform.runLater(() -> {
            loading.setVisible(false);
            loading.setManaged(false);
            if (error != null) {
                cards.getChildren().setAll(errorCard("Unable to load AniList genres: " + rootMessage(error)));
                return;
            }
            cards.getChildren().clear();
            List<String> genres = result.getKey();
            List<AniMedia> featured = result.getValue();
            genres.forEach(genre -> {
                AniMedia artwork = featured.stream()
                        .filter(media -> media.genres().stream().anyMatch(value -> value.equalsIgnoreCase(genre)))
                        .findFirst()
                        .orElse(null);
                cards.getChildren().add(genreCard(genre, artwork, activeButton));
            });
        }));
    }

    private Node genreCard(String genre, AniMedia artwork, Button genresButton) {
        ImageView art = new ImageView();
        art.setFitWidth(286);
        art.setFitHeight(164);
        art.setPreserveRatio(false);
        art.setSmooth(true);
        Rectangle artClip = new Rectangle(286, 164);
        artClip.setArcWidth(18);
        artClip.setArcHeight(18);
        art.setClip(artClip);
        String artUrl = artwork == null ? null : artwork.bannerImage();
        if ((artUrl == null || artUrl.isBlank()) && artwork != null) artUrl = artwork.coverImage();
        if (artUrl == null || artUrl.isBlank()) {
            var fallback = CatalogUiEnhancer.class.getResource("/images/aokuvue-moonlight.png");
            if (fallback != null) artUrl = fallback.toExternalForm();
        }
        if (artUrl != null) art.setImage(new Image(artUrl, 286, 164, false, true, true));

        Label kicker = new Label("A O K U V U E   C O L L E C T I O N");
        kicker.getStyleClass().add("genre-card-kicker");
        Label title = new Label(genre);
        title.getStyleClass().add("genre-card-title");
        Label caption = new Label("Explore " + genre.toLowerCase(Locale.ROOT) + " stories  →");
        caption.getStyleClass().add("genre-card-caption");
        caption.setWrapText(true);
        VBox copy = new VBox(5, kicker, title, caption);
        copy.setPadding(new Insets(16));
        copy.setAlignment(Pos.BOTTOM_LEFT);
        Region shade = new Region();
        shade.getStyleClass().add("genre-card-shade");
        shade.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        StackPane card = new StackPane(art, shade, copy);
        card.setPrefSize(286, 164);
        card.setMinSize(286, 164);
        card.setMaxSize(286, 164);
        card.getStyleClass().add("genre-card");
        StackPane.setAlignment(copy, Pos.BOTTOM_LEFT);
        card.setOnMouseClicked(event -> showPagedCatalog(
                genre,
                genre + " anime · AniList genre catalog",
                new CatalogFilter(MediaType.ANIME, "POPULARITY_DESC", null, null, genre, null, null, false),
                "ANIME",
                genresButton
        ));
        return card;
    }

    private void showCalendar(Button activeButton) {
        if (contentHost == null) return;
        setMainPage("ANIME");
        selectSidebar(activeButton);

        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        Label title = new Label("Calendar");
        title.getStyleClass().add("browse-title");
        Label nativeLine = new Label("放送スケジュール");
        nativeLine.getStyleClass().add("browse-native");
        Label subtitle = new Label("Upcoming episode releases from AniList · times shown in your system timezone");
        subtitle.getStyleClass().add("browse-subtitle");
        StackPane header = bannerHeader(new VBox(2, title, nativeLine, subtitle));
        header.getStyleClass().add("browse-header");

        ComboBox<Integer> year = new ComboBox<>();
        for (int value = today.getYear(); value <= today.getYear() + 3; value++) year.getItems().add(value);
        year.setValue(today.getYear());
        ComboBox<String> month = new ComboBox<>();
        month.getItems().add("All months");
        for (Month value : Month.values()) month.getItems().add(value.getDisplayName(TextStyle.FULL, Locale.getDefault()));
        month.setValue(today.getMonth().getDisplayName(TextStyle.FULL, Locale.getDefault()));
        Button todayButton = new Button("Current month");
        todayButton.getStyleClass().add("secondary-button");
        HBox controls = new HBox(9, new Label("Year"), year, new Label("Month"), month, todayButton);
        controls.setAlignment(Pos.CENTER_LEFT);
        controls.getStyleClass().add("browse-filter-bar");

        VBox releases = new VBox(14);
        Label count = new Label("Loading upcoming releases…");
        count.getStyleClass().add("search-count");
        Button loadMore = new Button("Load more");
        loadMore.getStyleClass().add("secondary-button");
        ProgressIndicator progress = new ProgressIndicator();
        progress.setPrefSize(24, 24);
        HBox paging = new HBox(10, count, progress, loadMore);
        paging.setAlignment(Pos.CENTER_LEFT);

        VBox body = new VBox(0, header, controls, releases, paging);
        body.setId(CALENDAR_VIEW_ID);
        body.getStyleClass().add("page-body");
        VBox.setMargin(releases, new Insets(18, AokuvueTheme.PAGE_GUTTER, 12, AokuvueTheme.PAGE_GUTTER));
        VBox.setMargin(paging, new Insets(0, AokuvueTheme.PAGE_GUTTER, 40, AokuvueTheme.PAGE_GUTTER));
        ScrollPane scroll = scroll(body);
        replaceContent(scroll);

        CalendarPager pager = new CalendarPager(year, month, releases, count, progress, loadMore, zone);
        year.setOnAction(event -> pager.reset());
        month.setOnAction(event -> pager.reset());
        todayButton.setOnAction(event -> {
            LocalDate now = LocalDate.now(zone);
            year.setValue(now.getYear());
            month.setValue(now.getMonth().getDisplayName(TextStyle.FULL, Locale.getDefault()));
            pager.reset();
        });
        loadMore.setOnAction(event -> pager.loadNext());
        scroll.vvalueProperty().addListener((obs, oldValue, newValue) -> {
            if (newValue.doubleValue() > 0.90) pager.loadNext();
        });
        pager.loadNext();
    }

    private final class CalendarPager {
        private final ComboBox<Integer> year;
        private final ComboBox<String> month;
        private final VBox releases;
        private final Label count;
        private final ProgressIndicator progress;
        private final Button loadMore;
        private final ZoneId zone;
        private final List<AiringRelease> items = new ArrayList<>();
        private final Set<Long> ids = new HashSet<>();
        private int page;
        private int generation;
        private boolean hasNext = true;
        private boolean loading;

        private CalendarPager(
                ComboBox<Integer> year,
                ComboBox<String> month,
                VBox releases,
                Label count,
                ProgressIndicator progress,
                Button loadMore,
                ZoneId zone
        ) {
            this.year = year;
            this.month = month;
            this.releases = releases;
            this.count = count;
            this.progress = progress;
            this.loadMore = loadMore;
            this.zone = zone;
        }

        private void reset() {
            generation++;
            page = 0;
            hasNext = true;
            loading = false;
            items.clear();
            ids.clear();
            releases.getChildren().clear();
            count.setText("Loading upcoming releases…");
            loadNext();
        }

        private void loadNext() {
            if (loading || !hasNext) return;
            DateRange range = selectedCalendarRange(year.getValue(), month.getValue(), zone);
            if (range == null || !range.to().isAfter(range.from())) {
                hasNext = false;
                progress.setVisible(false);
                loadMore.setDisable(true);
                count.setText("There are no upcoming releases in the selected period.");
                releases.getChildren().setAll(emptyCard("No upcoming AniList releases in this period."));
                return;
            }
            loading = true;
            progress.setVisible(true);
            loadMore.setDisable(true);
            int requested = page + 1;
            int token = generation;
            discovery.airing(range.from().getEpochSecond(), range.to().getEpochSecond(), requested)
                    .whenComplete((result, error) -> Platform.runLater(() -> {
                        if (token != generation) return;
                        loading = false;
                        progress.setVisible(false);
                        if (error != null) {
                            count.setText("AniList calendar error: " + rootMessage(error));
                            loadMore.setDisable(false);
                            return;
                        }
                        page = result.page();
                        hasNext = result.hasNextPage();
                        for (AiringRelease release : result.items()) if (ids.add(release.id())) items.add(release);
                        items.sort(Comparator.comparingLong(AiringRelease::airingAtEpochSeconds));
                        renderCalendar(releases, items, zone);
                        count.setText(items.size() + " upcoming releases loaded");
                        loadMore.setText(hasNext ? "Load more" : "All releases loaded");
                        loadMore.setDisable(!hasNext);
                    }));
        }
    }

    private record DateRange(Instant from, Instant to) {}

    private static DateRange selectedCalendarRange(Integer selectedYear, String selectedMonth, ZoneId zone) {
        if (selectedYear == null) return null;
        Instant now = Instant.now();
        LocalDate startDate;
        LocalDate endDate;
        if (selectedMonth == null || selectedMonth.equals("All months")) {
            startDate = LocalDate.of(selectedYear, 1, 1);
            endDate = startDate.plusYears(1);
        } else {
            Month month = monthFromDisplay(selectedMonth);
            if (month == null) return null;
            YearMonth ym = YearMonth.of(selectedYear, month);
            startDate = ym.atDay(1);
            endDate = ym.plusMonths(1).atDay(1);
        }
        Instant from = startDate.atStartOfDay(zone).toInstant();
        Instant to = endDate.atStartOfDay(zone).toInstant();
        if (to.isBefore(now) || to.equals(now)) return new DateRange(now, now);
        if (from.isBefore(now)) from = now.minusSeconds(1);
        return new DateRange(from, to);
    }

    private static Month monthFromDisplay(String display) {
        for (Month month : Month.values()) {
            if (month.getDisplayName(TextStyle.FULL, Locale.getDefault()).equalsIgnoreCase(display)) return month;
            if (month.name().equalsIgnoreCase(display)) return month;
        }
        return null;
    }

    private void renderCalendar(VBox target, List<AiringRelease> values, ZoneId zone) {
        target.getChildren().clear();
        LocalDate currentDay = null;
        VBox dayRows = null;
        for (AiringRelease release : values) {
            ZonedDateTime time = Instant.ofEpochSecond(release.airingAtEpochSeconds()).atZone(zone);
            LocalDate day = time.toLocalDate();
            if (!day.equals(currentDay)) {
                currentDay = day;
                Label heading = new Label(CALENDAR_DAY.format(day));
                heading.getStyleClass().add("section-title");
                dayRows = new VBox(8);
                VBox group = new VBox(8, heading, dayRows);
                target.getChildren().add(group);
            }
            if (dayRows != null) dayRows.getChildren().add(calendarRow(release, time));
        }
        if (target.getChildren().isEmpty()) target.getChildren().add(emptyCard("No upcoming releases found."));
    }

    private Node calendarRow(AiringRelease release, ZonedDateTime time) {
        AniMedia media = release.media();
        ImageView cover = new ImageView();
        cover.setFitWidth(64);
        cover.setFitHeight(90);
        cover.setPreserveRatio(false);
        cover.setSmooth(true);
        if (media.coverImage() != null && !media.coverImage().isBlank()) {
            cover.setImage(new Image(media.coverImage(), 64, 90, false, true, true));
        }
        Label timeLabel = new Label(CALENDAR_TIME.format(time));
        timeLabel.getStyleClass().add("section-kicker");
        Label title = new Label(media.title());
        title.getStyleClass().add("result-title");
        Label episode = new Label("Episode " + release.episode());
        episode.getStyleClass().add("genre-chip");
        Label meta = new Label(calendarMeta(media));
        meta.getStyleClass().add("poster-meta");
        VBox copy = new VBox(4, title, new HBox(7, episode, meta));
        HBox.setHgrow(copy, Priority.ALWAYS);
        Button details = new Button("Open");
        details.getStyleClass().add("secondary-button");
        details.setOnAction(event -> openDetails(media));
        HBox row = new HBox(14, timeLabel, cover, copy, details);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("search-result-row");
        row.setOnMouseClicked(event -> {
            if (!(event.getTarget() instanceof Button)) openDetails(media);
        });
        return row;
    }

    private static String calendarMeta(AniMedia media) {
        List<String> bits = new ArrayList<>();
        if (media.format() != null && !media.format().isBlank()) bits.add(media.format().replace('_', ' '));
        if (media.season() != null && !media.season().isBlank()) {
            bits.add(media.season() + (media.seasonYear() == null ? "" : " " + media.seasonYear()));
        }
        return String.join("  ·  ", bits);
    }

    private void removeLegacyGenreDropdown(Node node) {
        if (node instanceof HBox box) {
            List<Node> remove = box.getChildren().stream().filter(child -> {
                if (!(child instanceof ComboBox<?> combo)) return false;
                Object value = combo.getValue();
                return value instanceof String text && "Genres".equalsIgnoreCase(text.trim());
            }).toList();
            if (!remove.isEmpty()) box.getChildren().removeAll(remove);
        }
        if (node instanceof ScrollPane scroll && scroll.getContent() != null) removeLegacyGenreDropdown(scroll.getContent());
        if (node instanceof Parent parent) for (Node child : List.copyOf(parent.getChildrenUnmodifiable())) removeLegacyGenreDropdown(child);
        if (node instanceof TabPane tabs) for (Tab tab : tabs.getTabs()) if (tab.getContent() != null) removeLegacyGenreDropdown(tab.getContent());
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
        Label caption = new Label("EXPLICIT HENTAI"); caption.getStyleClass().add("category-caption");
        VBox copy = new VBox(2, title, caption); copy.setAlignment(Pos.BOTTOM_CENTER);
        StackPane tile = new StackPane(art, shade, copy);
        tile.setId(HENTAI_TILE_ID); tile.setPrefSize(190, 78); tile.getStyleClass().add("category-tile");
        StackPane.setAlignment(copy, Pos.BOTTOM_CENTER); StackPane.setMargin(copy, new Insets(0, 8, 9, 8));
        tile.setOnMouseClicked(event -> showPagedCatalog(
                "Hentai",
                "18+ AniList discovery · paged directly from AniList",
                new CatalogFilter(MediaType.ANIME, "POPULARITY_DESC", null, null, "Hentai", null, null, true),
                "HENTAI",
                sidebarButtons.get("Hentai")
        ));
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
        Label note = new Label("Read English MangaDex chapters in Aokuvue's native reader, or open another EverythingMoe-ranked provider in the embedded browser.");
        note.setWrapText(true); note.getStyleClass().add("source-status");
        ComboBox<ProviderSite> sources = new ComboBox<>(FXCollections.observableArrayList(mangaDirectory.latestSnapshot()));
        sources.setPrefWidth(320); if (!sources.getItems().isEmpty()) sources.getSelectionModel().selectFirst();
        Button nativeReader = new Button("Open native reader"); nativeReader.getStyleClass().add("primary-button");
        nativeReader.setOnAction(e -> invokeMangaReader(media));
        Button open = new Button("Open provider"); open.getStyleClass().add("secondary-button");
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
        FlowPane controls = new FlowPane(8, 8, nativeReader, sources, open, refresh); controls.setAlignment(Pos.CENTER_LEFT);
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

    private void openDetails(AniMedia media) {
        try {
            Method method = MainWindow.class.getDeclaredMethod("openDetails", AniMedia.class);
            method.setAccessible(true);
            method.invoke(root, media);
        } catch (Exception error) {
            System.err.println("[Aokuvue][Catalog] Unable to open details: " + error.getMessage());
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

    private void setMainPage(String pageName) {
        try {
            Field field = MainWindow.class.getDeclaredField("page");
            field.setAccessible(true);
            Object selected = null;
            for (Object value : field.getType().getEnumConstants()) {
                if (value instanceof Enum<?> enumValue && enumValue.name().equals(pageName)) {
                    selected = value;
                    break;
                }
            }
            if (selected != null) field.set(root, selected);
            Method update = MainWindow.class.getDeclaredMethod("updateNav");
            update.setAccessible(true);
            update.invoke(root);
        } catch (Exception error) {
            System.err.println("[Aokuvue][Catalog] Unable to sync navigation state: " + error.getMessage());
        }
    }

    private void selectSidebar(Button active) {
        clearSelectedNav(root);
        if (active != null && !active.getStyleClass().contains("selected")) active.getStyleClass().add("selected");
    }

    private static void clearSelectedNav(Node node) {
        if (node instanceof Button button && button.getStyleClass().contains("nav-tab")) {
            button.getStyleClass().remove("selected");
        }
        if (node instanceof ScrollPane scroll && scroll.getContent() != null) clearSelectedNav(scroll.getContent());
        if (node instanceof Parent parent) for (Node child : parent.getChildrenUnmodifiable()) clearSelectedNav(child);
    }

    private void replaceContent(Node view) {
        if (contentHost == null) return;
        replacingView = true;
        try {
            contentHost.getChildren().setAll(view);
        } finally {
            replacingView = false;
        }
    }

    private StackPane bannerHeader(Node copy) {
        StackPane header = new StackPane();
        header.setMinHeight(205);
        header.setPrefHeight(205);
        var brand = CatalogUiEnhancer.class.getResource("/images/aokuvue-moonlight.png");
        if (brand != null) {
            Region art = new Region();
            art.setBackground(new javafx.scene.layout.Background(new javafx.scene.layout.BackgroundImage(
                    new Image(brand.toExternalForm(), true),
                    javafx.scene.layout.BackgroundRepeat.NO_REPEAT,
                    javafx.scene.layout.BackgroundRepeat.NO_REPEAT,
                    javafx.scene.layout.BackgroundPosition.CENTER,
                    new javafx.scene.layout.BackgroundSize(100, 100, true, true, false, true)
            )));
            art.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
            art.getStyleClass().add("banner-art");
            header.getChildren().add(art);
        }
        Region shade = new Region();
        shade.getStyleClass().add("banner-scrim");
        shade.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        header.getChildren().add(shade);
        header.getChildren().add(copy);
        StackPane.setAlignment(copy, Pos.CENTER_LEFT);
        StackPane.setMargin(copy, new Insets(22, 34, 22, 34));
        return header;
    }

    private static ScrollPane scroll(Node content) {
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("page-scroll");
        return scroll;
    }

    private static Node emptyCard(String message) {
        Label label = new Label(message);
        label.setWrapText(true);
        VBox box = new VBox(label);
        box.setPadding(new Insets(18));
        box.getStyleClass().add("info-card");
        return box;
    }

    private static Node errorCard(String message) {
        Label title = new Label("AniList request failed");
        title.getStyleClass().add("section-title");
        Label detail = new Label(message);
        detail.setWrapText(true);
        VBox box = new VBox(8, title, detail);
        box.setPadding(new Insets(18));
        box.getStyleClass().add("info-card");
        return box;
    }

    private static List<String> yearOptions() {
        int current = LocalDate.now().getYear();
        List<String> values = new ArrayList<>();
        values.add("Any year");
        for (int year = current + 2; year >= 1950; year--) values.add(Integer.toString(year));
        return values;
    }

    private static String sortDisplay(String value) {
        if (value == null) return "Popular";
        return switch (value) {
            case "SCORE_DESC" -> "Score";
            case "TRENDING_DESC" -> "Trending";
            case "START_DATE_DESC" -> "Newest";
            default -> "Popular";
        };
    }

    private static String sortValue(String value) {
        if (value == null) return "POPULARITY_DESC";
        return switch (value) {
            case "Score" -> "SCORE_DESC";
            case "Trending" -> "TRENDING_DESC";
            case "Newest" -> "START_DATE_DESC";
            default -> "POPULARITY_DESC";
        };
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
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

    private static Button findSidebarButton(Node node, String name) {
        if (node instanceof Button button && button.getStyleClass().contains("nav-tab")) {
            String text = button.getText() == null ? "" : button.getText().trim();
            if (text.toLowerCase(Locale.ROOT).endsWith(name.toLowerCase(Locale.ROOT))) return button;
        }
        if (node instanceof ScrollPane scroll && scroll.getContent() != null) {
            Button found = findSidebarButton(scroll.getContent(), name); if (found != null) return found;
        }
        if (node instanceof Parent parent) for (Node child : parent.getChildrenUnmodifiable()) {
            Button found = findSidebarButton(child, name); if (found != null) return found;
        }
        return null;
    }

    private static Label findLabelByStyle(Node node, String style) {
        if (node instanceof Label label && label.getStyleClass().contains(style)) return label;
        if (node instanceof ScrollPane scroll && scroll.getContent() != null) {
            Label found = findLabelByStyle(scroll.getContent(), style); if (found != null) return found;
        }
        if (node instanceof Parent parent) for (Node child : parent.getChildrenUnmodifiable()) {
            Label found = findLabelByStyle(child, style); if (found != null) return found;
        }
        if (node instanceof TabPane tabs) for (Tab tab : tabs.getTabs()) if (tab.getContent() != null) {
            Label found = findLabelByStyle(tab.getContent(), style); if (found != null) return found;
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
        if (node instanceof ScrollPane scroll && scroll.getContent() != null) {
            ScrollPane found = findScrollByStyle(scroll.getContent(), style); if (found != null) return found;
        }
        if (node instanceof Parent parent) for (Node child : parent.getChildrenUnmodifiable()) {
            ScrollPane found = findScrollByStyle(child, style); if (found != null) return found;
        }
        return null;
    }

    private static TabPane findTabPaneByStyle(Node node, String style) {
        if (node instanceof TabPane tabs && tabs.getStyleClass().contains(style)) return tabs;
        if (node instanceof ScrollPane scroll && scroll.getContent() != null) {
            TabPane found = findTabPaneByStyle(scroll.getContent(), style); if (found != null) return found;
        }
        if (node instanceof Parent parent) for (Node child : parent.getChildrenUnmodifiable()) {
            TabPane found = findTabPaneByStyle(child, style); if (found != null) return found;
        }
        return null;
    }

    private static Node findById(Node node, String id) {
        if (id.equals(node.getId())) return node;
        if (node instanceof ScrollPane scroll && scroll.getContent() != null) {
            Node found = findById(scroll.getContent(), id); if (found != null) return found;
        }
        if (node instanceof Parent parent) for (Node child : parent.getChildrenUnmodifiable()) {
            Node found = findById(child, id); if (found != null) return found;
        }
        if (node instanceof TabPane tabs) for (Tab tab : tabs.getTabs()) if (tab.getContent() != null) {
            Node found = findById(tab.getContent(), id); if (found != null) return found;
        }
        return null;
    }

    private void invokeMangaReader(AniMedia media) {
        try {
            Method method = MainWindow.class.getDeclaredMethod("showMangaReader", AniMedia.class);
            method.setAccessible(true); method.invoke(root, media);
        } catch (Exception error) {
            System.err.println("[Aokuvue][Catalog] Unable to open native manga reader: " + error.getMessage());
        }
    }

    static boolean isEnhancedCatalogView(Node view) {
        return view != null && (findById(view, DISCOVERY_VIEW_ID) != null
                || findById(view, CALENDAR_VIEW_ID) != null
                || findById(view, GENRES_VIEW_ID) != null);
    }
}
