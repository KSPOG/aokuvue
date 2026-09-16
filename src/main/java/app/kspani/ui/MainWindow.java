package app.kspani.ui;

import app.kspani.anilist.AniListAuthService;
import app.kspani.app.AppContext;
import app.kspani.app.FeedbackService;
import app.kspani.domain.*;
import app.kspani.player.PlayerSession;
import app.kspani.player.PlaybackProgressRepository;
import app.kspani.player.PlayerSettings;
import app.kspani.player.SubtitleCue;
import app.kspani.player.SubtitleParser;
import app.kspani.source.*;
import javafx.animation.PauseTransition;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.concurrent.Worker;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.*;
import javafx.scene.shape.Rectangle;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.awt.Desktop;
import java.net.URI;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class MainWindow extends BorderPane {
    private enum Page { HOME, ANIME, HENTAI, MANGA, MY_LIST, SEARCH, SETTINGS, DETAILS, READER, PLAYER }

    private record SeasonChoice(AnimeSeasonRef season, int ordinal) {
        @Override public String toString() { return season.displayLabel(ordinal); }
    }

    private static final class SourceRecommendationView {
        private final VBox root;
        private final Label eyebrow;
        private final Label name;
        private final Label detail;
        private final Label badge;
        private final Button action;

        private SourceRecommendationView(VBox root, Label eyebrow, Label name, Label detail, Label badge, Button action) {
            this.root = root;
            this.eyebrow = eyebrow;
            this.name = name;
            this.detail = detail;
            this.badge = badge;
            this.action = action;
        }
    }

    private final AppContext app;
    private final StackPane content = new StackPane();
    private final Label status = new Label("Ready");
    private final TextField search = new TextField();
    private final Label accountName = new Label("Login");
    private final ImageView accountAvatar = new ImageView();
    private final Circle connectionDot = new Circle(5);
    private final Tooltip connectionTooltip = new Tooltip("Checking connection…");
    private Timeline connectionMonitor;
    private Node applicationTopBar;
    private Node applicationStatusBar;
    private final BorderPane applicationShell = new BorderPane();
    private final BorderPane applicationWorkspace = new BorderPane();
    private Node applicationSidebar;
    private Node applicationHeader;
    private Stage playerFullScreenStage;
    private ChangeListener<Boolean> playerFullScreenListener;
    private Runnable playerFullScreenRestore;
    private Cursor resizeCursor=Cursor.DEFAULT;
    private double resizeStartScreenX;
    private double resizeStartScreenY;
    private double resizeStartX;
    private double resizeStartY;
    private double resizeStartWidth;
    private double resizeStartHeight;

    private final Button homeNav = nav("Home");
    private final Button animeNav = nav("Anime");
    private final Button hentaiNav = nav("Hentai");
    private final Button mangaNav = nav("Manga");
    private final Button listNav = nav("My List");
    private final Button settingsNav = nav("Settings");

    private Page page = Page.HOME;
    private Page beforeDetails = Page.HOME;
    private Viewer viewer;
    private List<AniMedia> trendingAnime = List.of();
    private List<AniMedia> popularAnime = List.of();
    private List<AniMedia> adultAnime = List.of();
    private List<AniMedia> trendingManga = List.of();
    private List<AniMedia> animeList = List.of();
    private List<AniMedia> mangaList = List.of();
    private AniMedia currentMedia;
    private EpisodeLoadResult currentEpisodeLoad;
    private ListView<SourceEpisode> currentEpisodeList;
    private long episodeLoadGeneration;
    private long playerLoadGeneration;
    private boolean suppressSourceChange;
    private String homeLoadError;
    private final CompletableFuture<Void> initialContentReady = new CompletableFuture<>();

    public MainWindow(AppContext app) {
        this.app = app;
        getStyleClass().add("app-root");
        content.getStyleClass().add("content-host");
        applicationTopBar=null;
        applicationStatusBar=buildStatusBar();
        applicationSidebar=buildSidebar();
        applicationHeader=buildTopBar();
        applicationShell.getStyleClass().add("workspace-shell");
        applicationWorkspace.setTop(applicationHeader);
        applicationWorkspace.setCenter(content);
        applicationShell.setLeft(applicationSidebar);
        applicationShell.setCenter(applicationWorkspace);
        setTop(null);
        setCenter(applicationShell);
        setBottom(applicationStatusBar);
        wireNavigation();
        wireWindowResize();
        addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.isControlDown() && event.getCode() == KeyCode.K) {
                search.requestFocus();
                search.selectAll();
                event.consume();
                return;
            }
            if (page != Page.PLAYER || playerShortcutBlocked(event.getTarget())) return;
            if (event.getCode() == KeyCode.SPACE) {
                app.player().playPause();event.consume();
            } else if (event.getCode() == KeyCode.LEFT) {
                app.player().seekRelative(-app.player().settings().seekSeconds());event.consume();
            } else if (event.getCode() == KeyCode.RIGHT) {
                app.player().seekRelative(app.player().settings().seekSeconds());event.consume();
            }
        });
        app.player().setStatusConsumer(text -> Platform.runLater(() -> status.setText(text)));
        Page startPage=Page.HOME;
        try {
            Page configured=Page.valueOf(app.config().get("ui.startTab","HOME").toUpperCase());
            if(configured==Page.HOME||configured==Page.ANIME||configured==Page.HENTAI||configured==Page.MANGA||configured==Page.MY_LIST) startPage=configured;
        } catch(Exception ignored) {}
        show(startPage);
        loadHome();
        restoreViewer();
        startConnectionMonitor();
    }

    /** Completes after every initial Home request has reached a terminal state. */
    public CompletableFuture<Void> initialContentReady() {
        return initialContentReady;
    }

    private static boolean playerShortcutBlocked(Object target) {
        Node node = target instanceof Node value ? value : null;
        while (node != null) {
            if (node instanceof TextInputControl || node instanceof ComboBoxBase<?> || node instanceof Spinner<?>
                    || node instanceof Slider || node instanceof ListView<?> || node instanceof ListCell<?>) return true;
            node = node.getParent();
        }
        return false;
    }

    private Node buildWindowTitleBar() {
        ImageView icon=new ImageView();
        var iconResource=MainWindow.class.getResource("/images/aokuvue-icon.png");
        if(iconResource!=null)icon.setImage(new Image(iconResource.toExternalForm(),18,18,true,true));
        icon.setFitWidth(18);icon.setFitHeight(18);icon.setPreserveRatio(true);icon.setMouseTransparent(true);
        Label title=new Label("Aokuvue");title.getStyleClass().add("window-title");title.setMouseTransparent(true);
        Region spacer=new Region();HBox.setHgrow(spacer,Priority.ALWAYS);
        Button minimize=windowButton("—","Minimize");
        Button maximize=windowButton("□","Maximize or restore");
        Button close=windowButton("×","Close");close.getStyleClass().add("window-close-button");
        HBox bar=new HBox(8,icon,title,spacer,minimize,maximize,close);bar.setAlignment(Pos.CENTER_LEFT);bar.getStyleClass().add("window-title-bar");
        final double[] dragOffset={0,0};
        bar.setOnMousePressed(event->{
            if(event.getButton()!=MouseButton.PRIMARY)return;
            Stage stage=windowStage();if(stage==null)return;
            dragOffset[0]=event.getSceneX();dragOffset[1]=event.getSceneY();
        });
        bar.setOnMouseDragged(event->{
            if(event.getButton()!=MouseButton.PRIMARY)return;
            Stage stage=windowStage();if(stage==null||stage.isMaximized()||stage.isFullScreen())return;
            stage.setX(event.getScreenX()-dragOffset[0]);stage.setY(event.getScreenY()-dragOffset[1]);
        });
        bar.setOnMouseClicked(event->{
            if(event.getButton()==MouseButton.PRIMARY&&event.getClickCount()==2){Stage stage=windowStage();if(stage!=null&&!stage.isFullScreen()){stage.setMaximized(!stage.isMaximized());maximize.setText(stage.isMaximized()?"❐":"□");}}
        });
        minimize.setOnAction(event->{Stage stage=windowStage();if(stage!=null)stage.setIconified(true);});
        maximize.setOnAction(event->{Stage stage=windowStage();if(stage!=null){stage.setMaximized(!stage.isMaximized());maximize.setText(stage.isMaximized()?"❐":"□");}});
        close.setOnAction(event->{Stage stage=windowStage();if(stage!=null)stage.close();});
        return bar;
    }

    private static Button windowButton(String text,String tooltip) {
        Button button=new Button(text);button.getStyleClass().add("window-control-button");button.setTooltip(new Tooltip(tooltip));button.setFocusTraversable(false);return button;
    }

    private Stage windowStage() {
        return getScene()!=null&&getScene().getWindow() instanceof Stage stage?stage:null;
    }

    private Node buildTopBar() {
        Label searchGlyph = new Label("⌕");
        searchGlyph.getStyleClass().add("search-glyph");
        search.setPromptText("Search anime, stories, or worlds…");
        search.setPrefWidth(520);
        search.getStyleClass().add("top-search");
        search.setOnAction(e -> { if(page==Page.HENTAI)runHentaiSearch();else runSearch(page == Page.MANGA ? MediaType.MANGA : MediaType.ANIME); });
        search.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ESCAPE) search.clear(); });
        Label shortcut = new Label("Ctrl K");
        shortcut.getStyleClass().add("shortcut-badge");
        StackPane searchBox = new StackPane(search, searchGlyph, shortcut);
        StackPane.setAlignment(searchGlyph, Pos.CENTER_LEFT);
        StackPane.setMargin(searchGlyph, new Insets(0,0,0,13));
        StackPane.setAlignment(shortcut, Pos.CENTER_RIGHT);
        StackPane.setMargin(shortcut, new Insets(0,9,0,0));
        HBox.setHgrow(searchBox, Priority.ALWAYS);
        searchBox.setMaxWidth(620);
        Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
        Label bell = new Label("♧"); bell.getStyleClass().add("top-icon");
        StackPane connectionIndicator = new StackPane(connectionDot);
        connectionIndicator.getStyleClass().add("connection-indicator");
        Tooltip.install(connectionIndicator, connectionTooltip);
        connectionIndicator.setOnMouseEntered(e -> connectionTooltip.show(connectionIndicator,
                connectionIndicator.localToScreen(connectionIndicator.getBoundsInLocal()).getMinX() - 45,
                connectionIndicator.localToScreen(connectionIndicator.getBoundsInLocal()).getMaxY() + 7));
        connectionIndicator.setOnMouseExited(e -> connectionTooltip.hide());
        connectionIndicator.setMinSize(18,18);
        connectionIndicator.setPrefSize(18,18);
        accountAvatar.setFitWidth(34); accountAvatar.setFitHeight(34); accountAvatar.setPreserveRatio(true);
        StackPane avatar = new StackPane(accountAvatar); avatar.getStyleClass().add("top-avatar");
        Label accountTier = new Label("AniList"); accountTier.getStyleClass().add("account-tier");
        VBox profile = new VBox(1, accountName, accountTier); profile.getStyleClass().add("top-profile");
        HBox account = new HBox(9, avatar, profile); account.setAlignment(Pos.CENTER_LEFT);
        account.setOnMouseClicked(e -> { if(viewer==null) loginAniList(); else show(Page.MY_LIST); });
        Separator chromeDivider=new Separator();chromeDivider.setOrientation(javafx.geometry.Orientation.VERTICAL);chromeDivider.getStyleClass().add("chrome-divider");
        Button minimize=windowButton("—","Minimize");Button maximize=windowButton("□","Maximize or restore");Button close=windowButton("×","Close");close.getStyleClass().add("window-close-button");
        minimize.setOnAction(e->{Stage stage=windowStage();if(stage!=null)stage.setIconified(true);});
        maximize.setOnAction(e->{Stage stage=windowStage();if(stage!=null){stage.setMaximized(!stage.isMaximized());maximize.setText(stage.isMaximized()?"❐":"□");}});
        close.setOnAction(e->{Stage stage=windowStage();if(stage!=null)stage.close();});
        HBox top = new HBox(14, searchBox, spacer, bell, connectionIndicator, account, chromeDivider, minimize,maximize,close);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(8,0,8,26));
        top.getStyleClass().add("top-bar");
        final double[] drag={0,0};
        top.setOnMousePressed(e->{if(e.getButton()!=MouseButton.PRIMARY||e.getTarget() instanceof Button||e.getTarget() instanceof TextInputControl)return;Stage stage=windowStage();if(stage!=null){drag[0]=e.getSceneX();drag[1]=e.getSceneY();}});
        top.setOnMouseDragged(e->{Stage stage=windowStage();if(e.getButton()==MouseButton.PRIMARY&&stage!=null&&!stage.isMaximized()&&!stage.isFullScreen()){stage.setX(e.getScreenX()-drag[0]);stage.setY(e.getScreenY()-drag[1]);}});
        top.setOnMouseClicked(e->{if(e.getButton()==MouseButton.PRIMARY&&e.getClickCount()==2){Stage stage=windowStage();if(stage!=null){stage.setMaximized(!stage.isMaximized());maximize.setText(stage.isMaximized()?"❐":"□");}}});
        return top;
    }

    private void startConnectionMonitor() {
        connectionDot.getStyleClass().setAll("connection-dot", "connection-checking");
        checkConnectionQuality();
        connectionMonitor = new Timeline(new KeyFrame(Duration.seconds(10), e -> checkConnectionQuality()));
        connectionMonitor.setCycleCount(Timeline.INDEFINITE);
        connectionMonitor.play();
    }

    private void checkConnectionQuality() {
        CompletableFuture.supplyAsync(() -> {
            long started = System.nanoTime();
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL("https://graphql.anilist.co").openConnection();
                connection.setRequestMethod("HEAD");
                connection.setConnectTimeout(3500);
                connection.setReadTimeout(3500);
                connection.setUseCaches(false);
                int code = connection.getResponseCode();
                connection.disconnect();
                long latencyMs = Math.max(1L, (System.nanoTime() - started) / 1_000_000L);
                return new long[]{code >= 200 && code < 500 ? 1 : 0, latencyMs};
            } catch (Exception ignored) {
                return new long[]{0, -1};
            }
        }).thenAccept(result -> Platform.runLater(() -> {
            connectionDot.getStyleClass().removeAll("connection-checking", "connection-perfect", "connection-medium", "connection-bad");
            if (result[0] == 0) {
                connectionDot.getStyleClass().add("connection-bad");
                connectionTooltip.setText("Connection: Bad · unreachable");
            } else if (result[1] <= 180) {
                connectionDot.getStyleClass().add("connection-perfect");
                connectionTooltip.setText("Connection: Perfect · " + result[1] + " ms");
            } else if (result[1] <= 500) {
                connectionDot.getStyleClass().add("connection-medium");
                connectionTooltip.setText("Connection: Medium · " + result[1] + " ms");
            } else {
                connectionDot.getStyleClass().add("connection-bad");
                connectionTooltip.setText("Connection: Bad · " + result[1] + " ms");
            }
        }));
    }

    private Node buildSidebar() {
        Label brand = new Label("A O K U V U E");
        brand.getStyleClass().add("app-brand");
        Label brandKind = new Label("ANIME  ×  STORIES  ×  BEYOND");
        brandKind.getStyleClass().add("brand-kind");
        VBox brandBox = new VBox(7, brand, brandKind);
        brandBox.setAlignment(Pos.CENTER);
        brandBox.setOnMouseClicked(e -> show(Page.HOME));
        homeNav.setText("⌂     Home");animeNav.setText("⌕     Explore");hentaiNav.setText("18+   Hentai");listNav.setText("♡     My List");mangaNav.setText("▤     Manga");
        for (Button button : List.of(homeNav, animeNav, hentaiNav, mangaNav, listNav, settingsNav)) {
            button.setMaxWidth(Double.MAX_VALUE);
            button.setAlignment(Pos.CENTER_LEFT);
        }
        Button library=nav("▥     Library");library.setOnAction(e->show(Page.MY_LIST));
        Button calendar=nav("▦     Calendar");calendar.setOnAction(e->showCollection("Seasonal",popularAnime.stream().filter(m->"RELEASING".equals(m.releaseStatus())).toList()));
        Button movies=nav("▣     Movies");movies.setOnAction(e->showCollection("Movies",popularAnime.stream().filter(m->safe(m.format()).contains("MOVIE")).toList()));
        Button originals=nav("☆     Originals");originals.setOnAction(e->showCollection("Originals",popularAnime.stream().filter(m->"ORIGINAL".equalsIgnoreCase(m.sourceMaterial())).toList()));
        Button genres=nav("⌘     Genres");genres.setOnAction(e->show(Page.ANIME));
        Button curated=nav("◉     Curated");curated.setOnAction(e->showCollection("Curated",popularAnime.stream().filter(m->m.averageScore()!=null&&m.averageScore()>=80).toList()));
        for(Button button:List.of(library,calendar,movies,originals,genres,curated)){button.setMaxWidth(Double.MAX_VALUE);button.setAlignment(Pos.CENTER_LEFT);}
        Region divider=new Region();divider.getStyleClass().add("sidebar-divider");divider.setMinHeight(1);
        VBox nav = new VBox(4, homeNav, animeNav, library, calendar, listNav, divider, movies, hentaiNav, mangaNav, originals, genres, curated);
        nav.setFillWidth(true);
        settingsNav.setText("⚙     Settings");
        settingsNav.setOnAction(e -> show(Page.SETTINGS));
        Circle glow = new Circle(37, Color.TRANSPARENT); glow.getStyleClass().add("eclipse-glow");
        Circle shadow = new Circle(32, Color.web(AokuvueTheme.OBSIDIAN)); shadow.setTranslateX(9); shadow.setTranslateY(-5);
        StackPane eclipse = new StackPane(glow, shadow); eclipse.setPrefHeight(82);
        Label footer = new Label("S A M E   M O O N .\nD I F F E R E N T\nW O R L D S .");
        footer.getStyleClass().add("sidebar-motto"); footer.setAlignment(Pos.CENTER);
        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        VBox sidebar = new VBox(18, brandBox, nav, spacer, settingsNav, eclipse, footer);
        sidebar.setPadding(new Insets(30,14,22,14));
        sidebar.setPrefWidth(AokuvueTheme.SIDEBAR_WIDTH); sidebar.setMinWidth(AokuvueTheme.SIDEBAR_WIDTH);
        sidebar.getStyleClass().add("sidebar");
        return sidebar;
    }

    private void showCollection(String title,List<AniMedia> items){page=Page.ANIME;updateNav();content.getChildren().setAll(browseView(title,"Explore stories beyond the visible.",items.isEmpty()?popularAnime:items,List.of()));}

    private Node buildStatusBar() {
        Label dot = new Label("●");
        dot.getStyleClass().add("online-dot");
        HBox bar = new HBox(7, dot, status);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(5,14,6,14));
        bar.getStyleClass().add("status-bar");
        return bar;
    }

    private void wireNavigation() {
        homeNav.setOnAction(e -> show(Page.HOME));
        animeNav.setOnAction(e -> show(Page.ANIME));
        hentaiNav.setOnAction(e -> show(Page.HENTAI));
        mangaNav.setOnAction(e -> show(Page.MANGA));
        listNav.setOnAction(e -> show(Page.MY_LIST));
    }

    private void wireWindowResize() {
        final double margin=6;
        addEventFilter(MouseEvent.MOUSE_MOVED,event->{
            Stage stage=windowStage();
            if(stage==null||stage.isMaximized()||stage.isFullScreen()){resizeCursor=Cursor.DEFAULT;setCursor(Cursor.DEFAULT);return;}
            boolean left=event.getSceneX()<=margin,right=event.getSceneX()>=getWidth()-margin;
            boolean top=event.getSceneY()<=margin,bottom=event.getSceneY()>=getHeight()-margin;
            resizeCursor=top&&left?Cursor.NW_RESIZE:top&&right?Cursor.NE_RESIZE:bottom&&left?Cursor.SW_RESIZE:bottom&&right?Cursor.SE_RESIZE:top?Cursor.N_RESIZE:bottom?Cursor.S_RESIZE:left?Cursor.W_RESIZE:right?Cursor.E_RESIZE:Cursor.DEFAULT;
            setCursor(resizeCursor);
        });
        addEventFilter(MouseEvent.MOUSE_PRESSED,event->{
            if(event.getButton()!=MouseButton.PRIMARY||resizeCursor==Cursor.DEFAULT)return;
            Stage stage=windowStage();if(stage==null)return;
            resizeStartScreenX=event.getScreenX();resizeStartScreenY=event.getScreenY();resizeStartX=stage.getX();resizeStartY=stage.getY();resizeStartWidth=stage.getWidth();resizeStartHeight=stage.getHeight();event.consume();
        });
        addEventFilter(MouseEvent.MOUSE_DRAGGED,event->{
            if(resizeCursor==Cursor.DEFAULT)return;
            Stage stage=windowStage();if(stage==null||stage.isMaximized()||stage.isFullScreen())return;
            double dx=event.getScreenX()-resizeStartScreenX,dy=event.getScreenY()-resizeStartScreenY;
            boolean west=resizeCursor==Cursor.W_RESIZE||resizeCursor==Cursor.NW_RESIZE||resizeCursor==Cursor.SW_RESIZE;
            boolean east=resizeCursor==Cursor.E_RESIZE||resizeCursor==Cursor.NE_RESIZE||resizeCursor==Cursor.SE_RESIZE;
            boolean north=resizeCursor==Cursor.N_RESIZE||resizeCursor==Cursor.NW_RESIZE||resizeCursor==Cursor.NE_RESIZE;
            boolean south=resizeCursor==Cursor.S_RESIZE||resizeCursor==Cursor.SW_RESIZE||resizeCursor==Cursor.SE_RESIZE;
            if(west){double width=Math.max(stage.getMinWidth(),resizeStartWidth-dx);stage.setX(resizeStartX+resizeStartWidth-width);stage.setWidth(width);}else if(east)stage.setWidth(Math.max(stage.getMinWidth(),resizeStartWidth+dx));
            if(north){double height=Math.max(stage.getMinHeight(),resizeStartHeight-dy);stage.setY(resizeStartY+resizeStartHeight-height);stage.setHeight(height);}else if(south)stage.setHeight(Math.max(stage.getMinHeight(),resizeStartHeight+dy));
            event.consume();
        });
    }

    private void show(Page target) {
        if (page == Page.PLAYER && target != Page.PLAYER) {
            playerLoadGeneration++;
            exitPlayerFullScreen();
            app.player().close();
        }
        page = target;
        updateNav();
        Node node = switch (target) {
            case HOME -> homeView();
            case ANIME -> browseView("Anime", "AniList anime discovery", popularAnime, trendingAnime);
            case HENTAI -> browseView("Hentai", "18+ AniList discovery · ranked source directory in Settings", adultAnime, List.of());
            case MANGA -> browseView("Manga", "AniList manga discovery", trendingManga, List.of());
            case MY_LIST -> listView();
            case SEARCH -> searchPlaceholder();
            case SETTINGS -> settingsView();
            case DETAILS, READER, PLAYER -> content.getChildren().isEmpty() ? homeView() : content.getChildren().get(0);
        };
        if (target != Page.DETAILS && target != Page.READER && target != Page.PLAYER) content.getChildren().setAll(node);
    }

    private void updateNav() {
        for (Button b : List.of(homeNav, animeNav, hentaiNav, mangaNav, listNav, settingsNav)) b.getStyleClass().remove("selected");
        switch (page) {
            case HOME -> homeNav.getStyleClass().add("selected");
            case ANIME, SEARCH -> animeNav.getStyleClass().add("selected");
            case HENTAI -> hentaiNav.getStyleClass().add("selected");
            case MANGA -> mangaNav.getStyleClass().add("selected");
            case MY_LIST -> listNav.getStyleClass().add("selected");
            case SETTINGS -> settingsNav.getStyleClass().add("selected");
            case DETAILS, READER, PLAYER -> homeNav.getStyleClass().add("selected");
            default -> {}
        }
    }

    private Node homeView() {
        VBox body = new VBox(16);
        body.setPadding(new Insets(0,0,42,0));
        body.getStyleClass().add("page-body");
        List<AniMedia> featured=!trendingAnime.isEmpty()?trendingAnime:popularAnime;
        if (!featured.isEmpty()) body.getChildren().add(homeSpotlight(featured));
        else body.getChildren().add(homeLoadingState());

        Node localContinue = continueWatchingShelf();
        if (localContinue != null) { body.getChildren().add(localContinue); VBox.setMargin(localContinue,new Insets(0,AokuvueTheme.PAGE_GUTTER,0,AokuvueTheme.PAGE_GUTTER)); }
        if(!featured.isEmpty())addHomeSection(body,trendingRankedShelf(featured));

        if (viewer != null) {
            List<AniMedia> current = animeList.stream().filter(m -> m.listEntry().status() == MediaListStatus.CURRENT).toList();
            List<AniMedia> planning = animeList.stream().filter(m -> m.listEntry().status() == MediaListStatus.PLANNING).toList();
            if (!current.isEmpty()) addHomeSection(body,shelf("Watching on AniList", current));
            if (!planning.isEmpty()) addHomeSection(body,shelf("Planning", planning));
        }
        if (!popularAnime.isEmpty()) addHomeSection(body,shelf("Popular Anime", popularAnime));
        if (!trendingManga.isEmpty()) addHomeSection(body,shelf("Trending Manga", trendingManga));
        return scroll(body);
    }

    private void addHomeSection(VBox body,Node section){body.getChildren().add(section);VBox.setMargin(section,new Insets(0,AokuvueTheme.PAGE_GUTTER,0,AokuvueTheme.PAGE_GUTTER));}

    private Node homeLoadingState(){
        VBox state=new VBox(12);state.setAlignment(Pos.CENTER);state.setMinHeight(390);state.getStyleClass().add("home-loading");
        if(homeLoadError==null){state.getChildren().addAll(new ProgressIndicator(),new Label("Entering the Unseen…"));}
        else {Label message=new Label("AniList could not be reached.\n"+homeLoadError);message.setWrapText(true);message.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);Button retry=new Button("Retry AniList");retry.getStyleClass().add("primary-button");retry.setOnAction(e->loadHome());state.getChildren().addAll(new Label("A O K U V U E"),message,retry);}
        return state;
    }

    private Node homeSpotlight(List<AniMedia> media) {
        Node feature = hero(media.get(0));
        HBox categories = new HBox(12);
        String[] names = {"New Releases", "Fantasy", "Slice of Life", "Mystery", "Romance", "Supernatural", "Adventure"};
        for (int i=0;i<names.length && i<media.size();i++) categories.getChildren().add(categoryTile(names[i], media.get(i)));
        ScrollPane categoryShelf = new ScrollPane(categories); categoryShelf.setFitToHeight(true);
        categoryShelf.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER); categoryShelf.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        categoryShelf.getStyleClass().add("category-shelf");
        VBox composition = new VBox(14, feature, categoryShelf);
        VBox.setMargin(categoryShelf,new Insets(0,AokuvueTheme.PAGE_GUTTER,0,AokuvueTheme.PAGE_GUTTER));
        composition.getStyleClass().add("home-composition");
        return composition;
    }

    private Node trendingRankedShelf(List<AniMedia> media){
        Label heading=new Label("Trending on AOKUVUE");heading.getStyleClass().add("section-title");Region spacer=new Region();HBox.setHgrow(spacer,Priority.ALWAYS);Button browse=new Button("View All  →");browse.getStyleClass().add("text-button");browse.setOnAction(e->show(Page.ANIME));HBox header=new HBox(10,heading,spacer,browse);header.setAlignment(Pos.CENTER_LEFT);
        HBox queue=new HBox(14);List<AniMedia> picks=media.stream().limit(5).toList();for(int i=0;i<picks.size();i++)queue.getChildren().add(spotlightRow(picks.get(i),i+1));
        return new VBox(8,header,queue);
    }

    private Node categoryTile(String name, AniMedia media) {
        String artUrl = media.bannerImage()==null||media.bannerImage().isBlank()?media.coverImage():media.bannerImage();
        ImageView art=coverImage(artUrl,190,78);
        Region shade=new Region(); shade.getStyleClass().add("category-shade");
        Label title=new Label(name); title.getStyleClass().add("category-title");
        Label line=new Label(switch(name){case "New Releases"->"FRESH WORLDS";case "Fantasy"->"BEYOND REALITY";case "Romance"->"HEARTS ECHO";default->"STORIES BEYOND";});
        line.getStyleClass().add("category-caption");
        VBox copy=new VBox(2,title,line); copy.setAlignment(Pos.BOTTOM_CENTER);
        StackPane tile=new StackPane(art,shade,copy); tile.setPrefSize(190,78); tile.getStyleClass().add("category-tile");
        StackPane.setAlignment(copy,Pos.BOTTOM_CENTER); StackPane.setMargin(copy,new Insets(0,8,9,8));
        tile.setOnMouseClicked(e->{ List<AniMedia> catalog=!trendingAnime.isEmpty()?trendingAnime:popularAnime;List<AniMedia> filtered=catalog.stream().filter(m->name.equals("New Releases")||m.genres().stream().anyMatch(g->g.equalsIgnoreCase(name))).toList(); page=Page.ANIME; updateNav(); content.getChildren().setAll(browseView(name,"Stories beyond the visible.",filtered.isEmpty()?catalog:filtered,List.of())); });
        return tile;
    }

    private Node spotlightRow(AniMedia media, int index) {
        String artUrl=media.bannerImage()==null||media.bannerImage().isBlank()?media.coverImage():media.bannerImage();
        ImageView art = coverImage(artUrl, 150, 72);
        art.getStyleClass().add("spotlight-thumb");
        Label number = new Label(String.format("%02d", index));
        number.getStyleClass().add("spotlight-number");
        Label title = new Label(media.title());
        title.setWrapText(true);
        title.setMaxWidth(150);
        title.getStyleClass().add("spotlight-title");
        Label meta = new Label(meta(media));
        meta.getStyleClass().add("poster-meta");
        VBox copy = new VBox(3, title, meta);
        VBox ranked = new VBox(4,new HBox(8,number,art),copy);
        HBox row = new HBox(ranked);
        row.setPrefWidth(190); row.setMaxWidth(240); HBox.setHgrow(row,Priority.ALWAYS);
        row.setAlignment(Pos.TOP_LEFT);
        row.getStyleClass().add("spotlight-row");
        row.setOnMouseClicked(e -> openDetails(media));
        return row;
    }

    private Node continueWatchingShelf() {
        List<PlaybackProgressRepository.ContinueEntry> recent = app.playbackProgress().recent(18);
        if (recent.isEmpty()) return null;
        Map<Integer, AniMedia> known = new java.util.LinkedHashMap<>();
        for (List<AniMedia> collection : List.of(trendingAnime, popularAnime, animeList)) {
            for (AniMedia media : collection) known.putIfAbsent(media.id(), media);
        }
        Label heading = new Label("Continue Watching"); heading.getStyleClass().add("section-title");
        HBox cards = new HBox(14);
        for (PlaybackProgressRepository.ContinueEntry entry : recent) {
            AniMedia media = known.get(entry.mediaId());
            if (media == null) continue;
            cards.getChildren().add(continueCard(media, entry));
        }
        if (cards.getChildren().isEmpty()) return null;
        ScrollPane shelf = new ScrollPane(cards);
        shelf.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        shelf.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        shelf.setFitToHeight(true);
        shelf.getStyleClass().add("media-shelf");
        Region spacer=new Region();HBox.setHgrow(spacer,Priority.ALWAYS);Label viewAll=new Label("View All  →");viewAll.getStyleClass().add("shelf-action");HBox header=new HBox(10,heading,spacer,viewAll);header.setAlignment(Pos.CENTER_LEFT);
        VBox section = new VBox(7, header, shelf); section.getStyleClass().add("section-block");
        return section;
    }

    private Node continueCard(AniMedia media, PlaybackProgressRepository.ContinueEntry entry) {
        String artUrl = media.bannerImage() == null || media.bannerImage().isBlank() ? media.coverImage() : media.bannerImage();
        ImageView art = coverImage(artUrl, 250, 122);
        Region shade = new Region(); shade.getStyleClass().add("continue-shade");
        Label episode = new Label("EPISODE " + entry.episodeNumber()); episode.getStyleClass().add("continue-episode");
        Label title = new Label(media.title()); title.setWrapText(true); title.getStyleClass().add("continue-title");
        VBox copy = new VBox(3, episode, title); copy.setMaxWidth(220);
        StackPane.setAlignment(copy, Pos.BOTTOM_LEFT); StackPane.setMargin(copy, new Insets(0,14,15,14));
        StackPane artwork = new StackPane(art, shade, copy); artwork.setPrefSize(250,122);
        double progress = entry.durationMs() > 0 ? Math.min(1.0, Math.max(0.0, (double)entry.positionMs()/entry.durationMs())) : (entry.watched()?1.0:0.08);
        Region track = new Region(); track.getStyleClass().add("continue-track"); track.setPrefWidth(250);
        Region fill = new Region(); fill.getStyleClass().add("continue-fill"); fill.setPrefWidth(250*progress);
        StackPane progressBar = new StackPane(track,fill); progressBar.setAlignment(Pos.CENTER_LEFT); progressBar.setPrefHeight(3);
        VBox card = new VBox(artwork,progressBar); card.setPrefWidth(250); card.setMaxWidth(250); card.getStyleClass().add("continue-card");
        card.setOnMouseClicked(e -> openDetails(media));
        return card;
    }

    private Node hero(AniMedia media) {
        StackPane card = new StackPane();
        card.getStyleClass().add("hero");
        card.setMinHeight(390);
        card.setPrefHeight(430);

        String artUrl = media.bannerImage() == null || media.bannerImage().isBlank() ? media.coverImage() : media.bannerImage();
        Image heroImage = new Image(artUrl, true);
        BackgroundSize cover = new BackgroundSize(100, 100, true, true, false, true);
        BackgroundImage backgroundImage = new BackgroundImage(
                heroImage,
                BackgroundRepeat.NO_REPEAT,
                BackgroundRepeat.NO_REPEAT,
                BackgroundPosition.CENTER,
                cover
        );
        Region art = new Region();
        art.setBackground(new Background(backgroundImage));
        art.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        art.getStyleClass().add("hero-art");
        Region scrim = new Region();
        scrim.getStyleClass().add("hero-scrim");
        scrim.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);

        Label kicker = new Label("A O K U V U E   O R I G I N A L"); kicker.getStyleClass().add("hero-kicker");
        Label title = new Label(media.title()); title.setWrapText(true); title.getStyleClass().add("hero-title");
        Label nativeTitle = new Label(media.nativeTitle()==null?"":media.nativeTitle()); nativeTitle.getStyleClass().add("hero-native"); nativeTitle.setManaged(!nativeTitle.getText().isBlank()); nativeTitle.setVisible(!nativeTitle.getText().isBlank());
        Label editorial = new Label("S T O R I E S   B E Y O N D   T H E   V I S I B L E ."); editorial.getStyleClass().add("hero-editorial");
        Label meta = new Label(meta(media)); meta.getStyleClass().add("hero-meta");
        Label description = new Label(trim(media.description(),320)); description.setWrapText(true); description.getStyleClass().add("hero-description");
        FlowPane genres = new FlowPane(7,7);
        media.genres().stream().limit(3).forEach(genre -> { Label chip = new Label(genre.toUpperCase()); chip.getStyleClass().add("genre-chip"); genres.getChildren().add(chip); });
        Button details = new Button("▶   Watch Now"); details.getStyleClass().add("primary-button"); details.setOnAction(e -> openDetails(media));
        Button browse = new Button("＋  Add to My List"); browse.getStyleClass().add("glass-button"); browse.setOnAction(e -> openDetails(media));
        HBox actions = new HBox(10, details, browse);
        VBox copy = new VBox(9, kicker, title, nativeTitle, editorial, description, meta, genres, actions);
        copy.setMaxWidth(560);
        copy.setAlignment(Pos.CENTER_LEFT);
        StackPane.setAlignment(copy, Pos.CENTER_LEFT);
        StackPane.setMargin(copy, new Insets(34,42,34,42));
        card.getChildren().addAll(art, scrim, copy);
        return card;
    }

    private Node shelf(String title, List<AniMedia> media) {
        Label heading = new Label(title); heading.getStyleClass().add("section-title");
        Label accent = new Label("CURATED FOR YOU"); accent.getStyleClass().add("section-kicker");
        Region headerSpacer = new Region(); HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        Button all = new Button("Explore all  →"); all.getStyleClass().add("shelf-action"); all.setOnAction(e -> show(title.toLowerCase().contains("manga") ? Page.MANGA : Page.ANIME));
        HBox header = new HBox(10, heading, headerSpacer, all); header.setAlignment(Pos.CENTER_LEFT);
        HBox cards = new HBox(15);
        for (AniMedia m : media.stream().limit(18).toList()) cards.getChildren().add(new MediaCard(m, this::openDetails));
        if (cards.getChildren().isEmpty()) cards.getChildren().add(new Label("No items."));
        ScrollPane shelf = new ScrollPane(cards);
        shelf.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        shelf.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        shelf.setFitToHeight(true);
        shelf.getStyleClass().add("media-shelf");
        VBox section = new VBox(5, accent, header, shelf);
        section.getStyleClass().add("section-block");
        return section;
    }

    private Node browseView(String titleText, String subtitleText, List<AniMedia> primary, List<AniMedia> secondary) {
        Label title = new Label(titleText); title.getStyleClass().add("browse-title");
        Label nativeLine = new Label("まだ見ぬ物語を"); nativeLine.getStyleClass().add("browse-native");
        Label subtitle = new Label(subtitleText); subtitle.getStyleClass().add("browse-subtitle");
        List<AniMedia> merged = new ArrayList<>(primary);
        for (AniMedia m : secondary) if (merged.stream().noneMatch(x -> x.id() == m.id())) merged.add(m);
        TilePane grid = new TilePane(); grid.setHgap(16); grid.setVgap(18); grid.setPrefTileWidth(190); grid.setAlignment(Pos.TOP_LEFT);
        Runnable render=()->{grid.getChildren().clear();merged.forEach(m->grid.getChildren().add(new MediaCard(m,this::openDetails)));}; render.run();
        HBox filters = new HBox(9);
        for(String filter:List.of("All","TV","Movie","OVA","Originals")){
            Button b=new Button(filter); b.getStyleClass().add(filter.equals("All")?"filter-chip-selected":"filter-chip");
            b.setOnAction(e->{filters.getChildren().forEach(n->{n.getStyleClass().remove("filter-chip-selected");if(!n.getStyleClass().contains("filter-chip"))n.getStyleClass().add("filter-chip");});b.getStyleClass().remove("filter-chip");b.getStyleClass().add("filter-chip-selected");grid.getChildren().clear();merged.stream().filter(m->filter.equals("All")||(filter.equals("Originals")&&"ORIGINAL".equalsIgnoreCase(m.sourceMaterial()))||safe(m.format()).replace('_',' ').equalsIgnoreCase(filter)).forEach(m->grid.getChildren().add(new MediaCard(m,this::openDetails)));});
            filters.getChildren().add(b);
        }
        List<String> genreValues=new ArrayList<>();genreValues.add("Genres");merged.stream().flatMap(m->m.genres().stream()).distinct().sorted().forEach(genreValues::add);ComboBox<String> genre=new ComboBox<>(FXCollections.observableArrayList(genreValues));genre.setValue("Genres");genre.setOnAction(e->{String value=genre.getValue();if(value==null||value.equals("Genres"))return;grid.getChildren().clear();merged.stream().filter(m->m.genres().contains(value)).forEach(m->grid.getChildren().add(new MediaCard(m,this::openDetails)));});
        List<String> yearValues=new ArrayList<>();yearValues.add("Year");merged.stream().map(AniMedia::seasonYear).filter(java.util.Objects::nonNull).distinct().sorted(Comparator.reverseOrder()).map(String::valueOf).forEach(yearValues::add);ComboBox<String> year=new ComboBox<>(FXCollections.observableArrayList(yearValues));year.setValue("Year");year.setOnAction(e->{if(year.getValue()==null||year.getValue().equals("Year"))return;int selected=Integer.parseInt(year.getValue());grid.getChildren().clear();merged.stream().filter(m->m.seasonYear()!=null&&m.seasonYear()==selected).forEach(m->grid.getChildren().add(new MediaCard(m,this::openDetails)));});
        ComboBox<String> sort=new ComboBox<>(FXCollections.observableArrayList("Popular","Score","Title"));sort.setValue("Popular");sort.getStyleClass().add("browse-sort");
        sort.setOnAction(e->{Comparator<AniMedia> c=switch(sort.getValue()){case "Score"->Comparator.comparing(m->m.averageScore()==null?0:m.averageScore(),Comparator.reverseOrder());case "Title"->Comparator.comparing(AniMedia::title,String.CASE_INSENSITIVE_ORDER);default->Comparator.comparing(m->m.popularity()==null?0:m.popularity(),Comparator.reverseOrder());};merged.sort(c);render.run();});
        Region spacer=new Region();HBox.setHgrow(spacer,Priority.ALWAYS);
        HBox filterBar=new HBox(9,filters,genre,year,spacer,new Label("Sort"),sort);filterBar.setAlignment(Pos.CENTER_LEFT);filterBar.getStyleClass().add("browse-filter-bar");
        StackPane heading=bannerHeader(primary.isEmpty()?null:primary.get(0),new VBox(2,title,nativeLine,subtitle)); heading.getStyleClass().add("browse-header");
        VBox body = new VBox(0, heading, filterBar, grid);
        VBox.setMargin(grid,new Insets(18,AokuvueTheme.PAGE_GUTTER,40,AokuvueTheme.PAGE_GUTTER)); body.getStyleClass().add("page-body");
        return scroll(body);
    }

    private StackPane bannerHeader(AniMedia media, Node copy) {
        StackPane header=new StackPane();header.setMinHeight(205);header.setPrefHeight(205);
        var brand=MainWindow.class.getResource("/images/aokuvue-moonlight.png");String url=brand==null?null:brand.toExternalForm();if(url!=null){Region art=new Region();art.setBackground(new Background(new BackgroundImage(new Image(url,true),BackgroundRepeat.NO_REPEAT,BackgroundRepeat.NO_REPEAT,BackgroundPosition.CENTER,new BackgroundSize(100,100,true,true,false,true))));art.setMaxSize(Double.MAX_VALUE,Double.MAX_VALUE);art.getStyleClass().add("banner-art");header.getChildren().add(art);}
        Region shade=new Region();shade.getStyleClass().add("banner-scrim");shade.setMaxSize(Double.MAX_VALUE,Double.MAX_VALUE);header.getChildren().add(shade);
        header.getChildren().add(copy);StackPane.setAlignment(copy,Pos.CENTER_LEFT);StackPane.setMargin(copy,new Insets(22,34,22,34));return header;
    }

    private Node listView() {
        VBox body = new VBox(18); body.setPadding(new Insets(24,30,40,30)); body.getStyleClass().add("page-body");
        Label title = new Label("My List"); title.getStyleClass().add("browse-title");
        body.getChildren().add(title);
        if (viewer == null) {
            VBox prompt = new VBox(12, new Label("Connect AniList to load Watching, Planning, Completed, Paused, Dropped and Repeating."));
            Button login = new Button("Login with AniList"); login.getStyleClass().add("primary-button"); login.setOnAction(e -> loginAniList());
            prompt.getChildren().add(login); prompt.getStyleClass().add("empty-state"); body.getChildren().add(prompt);
            return scroll(body);
        }
        TabPane mediaTabs = new TabPane();
        mediaTabs.getTabs().add(new Tab("Anime", statusTabs(animeList, MediaType.ANIME)));
        mediaTabs.getTabs().add(new Tab("Manga", statusTabs(mangaList, MediaType.MANGA)));
        mediaTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        body.getChildren().add(mediaTabs);
        VBox.setVgrow(mediaTabs, Priority.ALWAYS);
        return body;
    }

    private Node statusTabs(List<AniMedia> library, MediaType type) {
        TabPane tabs = new TabPane();
        for (MediaListStatus s : MediaListStatus.values()) {
            List<AniMedia> entries = library.stream().filter(m -> m.listEntry().status() == s).toList();
            tabs.getTabs().add(new Tab(s.displayName(type) + " (" + entries.size() + ")", gridScroll(entries)));
        }
        tabs.getTabs().add(new Tab("All (" + library.size() + ")", gridScroll(library)));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        return tabs;
    }

    private Node gridScroll(List<AniMedia> entries) {
        TilePane grid = new TilePane(); grid.setHgap(16); grid.setVgap(20); grid.setPadding(new Insets(18));
        entries.forEach(m -> grid.getChildren().add(new MediaCard(m, this::openDetails)));
        ScrollPane s = new ScrollPane(grid); s.setFitToWidth(true); s.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER); s.getStyleClass().add("page-scroll");
        return s;
    }

    private Node searchPlaceholder() {
        VBox v = new VBox(new Label("Search AniList from the top bar.")); v.setAlignment(Pos.CENTER); return v;
    }

    private void runSearch(MediaType type) {
        String q = search.getText().trim(); if (q.isBlank()) return;
        page = Page.SEARCH; updateNav(); status.setText("Searching AniList for “" + q + "”…");
        VBox loading = new VBox(12, new ProgressIndicator(), new Label("Searching…")); loading.setAlignment(Pos.CENTER); content.getChildren().setAll(loading);
        app.anilist().search(q, type, 36).whenComplete((items,error) -> Platform.runLater(() -> {
            if (error != null) { status.setText("Search failed: " + root(error)); content.getChildren().setAll(errorCard(root(error))); return; }
            status.setText("Found " + items.size() + " results.");
            content.getChildren().setAll(searchResultsView(q, items));
        }));
    }

    private Node searchResultsView(String query, List<AniMedia> items) {
        Label title=new Label("Search Results");title.getStyleClass().add("browse-title");
        Label subtitle=new Label("Stories find you, too.");subtitle.getStyleClass().add("browse-subtitle");
        StackPane header=bannerHeader(items.isEmpty()?null:items.get(0),new VBox(4,title,subtitle));header.getStyleClass().add("search-header");
        Label count=new Label(items.size()+" results for “"+query+"”");count.getStyleClass().add("search-count");
        VBox rows=new VBox(8);Runnable showAll=()->{rows.getChildren().clear();for(int i=0;i<items.size();i++)rows.getChildren().add(searchResultRow(items.get(i),i+1));};showAll.run();
        HBox categories=new HBox(10);for(String category:List.of("All","Anime","Movies","OVAs","Originals")){Button button=new Button(category);button.getStyleClass().add(category.equals("All")?"filter-chip-selected":"filter-chip");button.setOnAction(e->{categories.getChildren().forEach(n->{n.getStyleClass().remove("filter-chip-selected");if(!n.getStyleClass().contains("filter-chip"))n.getStyleClass().add("filter-chip");});button.getStyleClass().remove("filter-chip");button.getStyleClass().add("filter-chip-selected");List<AniMedia> filtered=items.stream().filter(m->category.equals("All")||category.equals("Anime")&&m.type()==MediaType.ANIME||category.equals("Movies")&&safe(m.format()).contains("MOVIE")||category.equals("OVAs")&&safe(m.format()).contains("OVA")||category.equals("Originals")&&"ORIGINAL".equalsIgnoreCase(m.sourceMaterial())).toList();rows.getChildren().clear();for(int i=0;i<filtered.size();i++)rows.getChildren().add(searchResultRow(filtered.get(i),i+1));count.setText(filtered.size()+" results for “"+query+"”");});categories.getChildren().add(button);}
        VBox body=new VBox(16,header,categories,count,rows);body.setPadding(new Insets(0,AokuvueTheme.PAGE_GUTTER,40,AokuvueTheme.PAGE_GUTTER));VBox.setMargin(header,new Insets(0,-AokuvueTheme.PAGE_GUTTER,0,-AokuvueTheme.PAGE_GUTTER));body.getStyleClass().add("page-body");return scroll(body);
    }

    private Node searchResultRow(AniMedia media,int index){
        String url=media.bannerImage()==null||media.bannerImage().isBlank()?media.coverImage():media.bannerImage();ImageView art=coverImage(url,250,112);
        Label number=new Label(Integer.toString(index));number.getStyleClass().add("result-number");
        Label title=new Label(media.title());title.getStyleClass().add("result-title");
        Label nativeTitle=new Label(media.nativeTitle()==null?"":media.nativeTitle());nativeTitle.getStyleClass().add("result-native");
        Label metadata=new Label(meta(media));metadata.getStyleClass().add("poster-meta");
        FlowPane genres=new FlowPane(6,6);media.genres().stream().limit(4).forEach(g->{Label chip=new Label(g);chip.getStyleClass().add("genre-chip");genres.getChildren().add(chip);});
        VBox copy=new VBox(3,title,nativeTitle,metadata,genres);copy.setPrefWidth(360);HBox.setHgrow(copy,Priority.ALWAYS);
        Label description=new Label(trim(media.description(),180));description.setWrapText(true);description.setMaxWidth(360);description.getStyleClass().add("result-description");
        Button watch=new Button("▶  Watch");watch.getStyleClass().add("secondary-button");watch.setOnAction(e->openDetails(media));
        HBox row=new HBox(16,number,art,copy,description,watch);row.setAlignment(Pos.CENTER_LEFT);row.getStyleClass().add("search-result-row");return row;
    }

    private void openDetails(AniMedia preview) {
        beforeDetails = page == Page.DETAILS || page == Page.READER || page == Page.PLAYER ? Page.HOME : page;
        page = Page.DETAILS; updateNav(); currentEpisodeLoad=null; currentEpisodeList=null; ++episodeLoadGeneration; status.setText("Loading " + preview.title() + "…");
        content.getChildren().setAll(loadingCard("Loading media details…"));
        app.anilist().details(preview.id(), preview.type()).whenComplete((media,error) -> Platform.runLater(() -> {
            if (error != null) { status.setText("Details failed: " + root(error)); content.getChildren().setAll(errorCard(root(error))); return; }
            currentMedia = media;
            status.setText(media.title());
            content.getChildren().setAll(detailsView(media));
        }));
    }

    private Node detailsView(AniMedia media) {
        VBox body = new VBox(0); body.getStyleClass().add("page-body");
        Button back = new Button("‹  Back"); back.getStyleClass().add("detail-back"); back.setOnAction(e -> show(beforeDetails));
        StackPane head = detailHero(media,back);
        body.getChildren().add(head);
        if (media.type() == MediaType.ANIME) {
            SourceRecommendationView recommendation = sourceRecommendation(media);
            VBox episodes=new VBox(episodeSection(media,recommendation));episodes.setPadding(new Insets(10,AokuvueTheme.PAGE_GUTTER,28,AokuvueTheme.PAGE_GUTTER));
            VBox about=new VBox(14,recommendation.root,mediaInfo(media));about.setPadding(new Insets(18,AokuvueTheme.PAGE_GUTTER,38,AokuvueTheme.PAGE_GUTTER));
            if(!media.streamingServices().isEmpty()||!media.streamingEpisodes().isEmpty())about.getChildren().add(officialStreamingPanel(media));
            List<AniMedia> related=trendingAnime.stream().filter(candidate->candidate.id()!=media.id()&&candidate.genres().stream().anyMatch(media.genres()::contains)).limit(12).toList();VBox more=new VBox(shelf("More Like This",related));more.setPadding(new Insets(16,AokuvueTheme.PAGE_GUTTER,30,AokuvueTheme.PAGE_GUTTER));
            TabPane tabs=new TabPane();tabs.getStyleClass().add("detail-tabs");tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);tabs.getTabs().addAll(new Tab("Episodes",episodes),new Tab("About",about),new Tab("More Like This",more));
            body.getChildren().add(tabs);
        } else {
            VBox about=new VBox(14,mediaInfo(media));about.setPadding(new Insets(20,AokuvueTheme.PAGE_GUTTER,38,AokuvueTheme.PAGE_GUTTER));body.getChildren().add(about);
        }
        return scroll(body);
    }

    private StackPane detailHero(AniMedia media,Button back){
        StackPane hero=new StackPane();hero.setMinHeight(460);hero.setPrefHeight(500);hero.getStyleClass().add("detail-hero");
        String url=media.bannerImage()==null||media.bannerImage().isBlank()?media.coverImage():media.bannerImage();Region art=new Region();art.setBackground(new Background(new BackgroundImage(new Image(url,true),BackgroundRepeat.NO_REPEAT,BackgroundRepeat.NO_REPEAT,BackgroundPosition.CENTER,new BackgroundSize(100,100,true,true,false,true))));art.setMaxSize(Double.MAX_VALUE,Double.MAX_VALUE);art.getStyleClass().add("detail-hero-art");
        Region scrim=new Region();scrim.setMaxSize(Double.MAX_VALUE,Double.MAX_VALUE);scrim.getStyleClass().add("detail-hero-scrim");
        Label eyebrow=new Label("A O K U V U E   S E L E C T I O N");eyebrow.getStyleClass().add("hero-kicker");
        Label title=new Label(media.title());title.setWrapText(true);title.getStyleClass().add("detail-hero-title");
        Label nativeTitle=new Label(media.nativeTitle()==null?"":media.nativeTitle());nativeTitle.getStyleClass().add("hero-native");nativeTitle.setManaged(!nativeTitle.getText().isBlank());
        Label desc=new Label(trim(media.description(),430));desc.setWrapText(true);desc.getStyleClass().add("detail-description");
        Label metadata=new Label(meta(media));metadata.getStyleClass().add("detail-meta");
        FlowPane genres=new FlowPane(7,7);media.genres().stream().limit(5).forEach(g->{Label chip=new Label(g);chip.getStyleClass().add("genre-chip");genres.getChildren().add(chip);});
        Button episodes=new Button(media.type()==MediaType.ANIME?"▶  View Episodes":"View Details");episodes.getStyleClass().add("primary-button");episodes.setOnAction(e->status.setText(media.type()==MediaType.ANIME?"Episodes are listed below.":media.title()));
        Button ani=new Button("＋  Add to My List");ani.getStyleClass().add("secondary-button");ani.setOnAction(e->openUri(media.siteUri()));ani.setDisable(media.siteUri()==null);
        VBox copy=new VBox(9,eyebrow,title,nativeTitle,desc,metadata,genres,new HBox(10,episodes,ani));copy.setMaxWidth(610);StackPane.setAlignment(copy,Pos.CENTER_LEFT);StackPane.setMargin(copy,new Insets(45,38,32,38));
        StackPane.setAlignment(back,Pos.TOP_LEFT);StackPane.setMargin(back,new Insets(14,0,0,18));hero.getChildren().addAll(art,scrim,copy,back);return hero;
    }

    private SourceRecommendationView sourceRecommendation(AniMedia media) {
        Label eyebrow = new Label("RECOMMENDED SOURCE");
        eyebrow.getStyleClass().add("recommendation-eyebrow");
        Label name = new Label("Finding the best source…");
        name.getStyleClass().add("recommendation-title");
        Label detail = new Label("Aokuvue checks configured playback sources and verifies the series match before playback.");
        detail.setWrapText(true);
        detail.getStyleClass().add("recommendation-detail");
        Label badge = new Label("CHECKING");
        badge.getStyleClass().add("recommendation-badge");

        VBox copy = new VBox(4, eyebrow, name, detail);
        HBox.setHgrow(copy, Priority.ALWAYS);
        copy.setMaxWidth(Double.MAX_VALUE);
        Button directory = new Button("View EverythingMoe ↗");
        directory.getStyleClass().add("text-button");
        directory.setOnAction(e -> openUri(EverythingMoeProviderDirectory.DIRECTORY_URI));
        VBox actions = new VBox(7, badge, directory);
        actions.setAlignment(Pos.CENTER_RIGHT);
        HBox row = new HBox(18, copy, actions);
        row.setAlignment(Pos.CENTER_LEFT);
        VBox card = new VBox(row);
        card.setPadding(new Insets(17, 19, 17, 19));
        card.getStyleClass().add("source-recommendation");

        SourceRecommendationView view = new SourceRecommendationView(card, eyebrow, name, detail, badge, directory);
        List<AnimeSource> configured = app.sources().configuredUserVisibleSources();
        AnimeSource initial = preferredSourceFor(media, configured);
        showRecommendationChecking(view, initial);
        return view;
    }

    private AnimeSource preferredSourceFor(AniMedia media, List<AnimeSource> configured) {
        var saved = app.sourceState().loadSelection(media.id())
                .flatMap(selection -> app.sources().get(selection.sourceId()))
                .filter(AnimeSource::isConfigured)
                .filter(source -> source.descriptor().capabilities().directPlayback());
        if (saved.isPresent()) return saved.get();
        return configured.stream()
                .filter(source -> app.providerDirectory().rankFor(source).isPresent())
                .min(Comparator.comparingInt(source -> app.providerDirectory().rankFor(source).orElse(Integer.MAX_VALUE)))
                .orElseGet(() -> configured.isEmpty() ? null : configured.get(0));
    }

    private void showRecommendationChecking(SourceRecommendationView view, AnimeSource source) {
        view.eyebrow.setText("RECOMMENDED SOURCE");
        if (source == null) {
            ProviderSite recommended=app.providerDirectory().recommendedSite().orElse(null);
            if(recommended==null){
                view.name.setText("EverythingMoe directory unavailable");
                view.detail.setText("No provider entry is currently available from the directory.");
                view.badge.setText("UNAVAILABLE");
                view.action.setText("View EverythingMoe ↗");
                view.action.setOnAction(e->openUri(EverythingMoeProviderDirectory.DIRECTORY_URI));
                return;
            }
            view.name.setText(recommended.name());
            view.detail.setText("EverythingMoe rank #"+recommended.rank()+" · automatically selected external source"
                    +(recommended.multiSource()?" · MULT aggregator":"")+". Episode availability is verified on the provider website.");
            view.badge.setText("EXTERNAL");
            view.action.setText("Open source ↗");
            view.action.setOnAction(e->openUri(recommended.baseUri()));
            return;
        }
        view.name.setText(source.descriptor().name());
        var rank = app.providerDirectory().rankFor(source);
        view.detail.setText(rank.isPresent()
                ? "EverythingMoe rank #" + rank.getAsInt() + " among anime streaming sites · checking this title now."
                : "Best configured in-app source · checking that this exact series is available.");
        view.badge.setText("CHECKING");
    }

    private void showRecommendationVerified(SourceRecommendationView view, EpisodeLoadResult loaded) {
        boolean internalIndex = "tracking".equals(loaded.source().descriptor().id());
        if (internalIndex) {
            view.eyebrow.setText("SOURCE STATUS");
            view.name.setText("No source confirmed yet");
            view.detail.setText("Episodes are indexed for tracking. Selecting Watch will try configured playback sources for this episode.");
            view.badge.setText("INDEX ONLY");
            return;
        }
        view.eyebrow.setText("RECOMMENDED FOR THIS SERIES");
        view.name.setText(loaded.source().descriptor().name());
        var rank = app.providerDirectory().rankFor(loaded.source());
        String directoryRank = rank.isPresent() ? " · EverythingMoe rank #" + rank.getAsInt() : "";
        view.detail.setText("Verified match: " + loaded.series().name() + " · " + loaded.episodes().size()
                + " episodes" + directoryRank + ".");
        view.badge.setText("VERIFIED");
    }

    private Node seasonSelector(AniMedia media) {
        HBox row = new HBox(8);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("season-selector-row");
        row.setVisible(false);
        row.setManaged(false);
        String format = media.format()==null?"":media.format();
        if (!("TV".equalsIgnoreCase(format) || "TV_SHORT".equalsIgnoreCase(format) || "ONA".equalsIgnoreCase(format))) return row;

        Label label = new Label("Season");
        label.getStyleClass().add("field-label");
        ComboBox<SeasonChoice> seasons = new ComboBox<>();
        seasons.setPrefWidth(360);
        row.getChildren().addAll(label, seasons);

        app.anilist().relatedAnimeSeasons(media).whenComplete((related,error) -> Platform.runLater(() -> {
            if (error != null || related == null || related.size() <= 1) return;
            if (currentMedia == null || currentMedia.id() != media.id()) return;
            List<SeasonChoice> choices = new ArrayList<>();
            for (int i=0;i<related.size();i++) choices.add(new SeasonChoice(related.get(i), i+1));
            seasons.setItems(FXCollections.observableArrayList(choices));
            choices.stream().filter(c -> c.season().mediaId()==media.id()).findFirst().ifPresent(seasons::setValue);
            row.setManaged(true);
            row.setVisible(true);
            seasons.setOnAction(e -> {
                SeasonChoice choice = seasons.getValue();
                if (choice == null || choice.season().mediaId() == media.id()) return;
                openSeason(choice.season());
            });
        }));
        return row;
    }

    private void openSeason(AnimeSeasonRef season) {
        page = Page.DETAILS;
        updateNav();
        currentEpisodeLoad = null;
        currentEpisodeList = null;
        ++episodeLoadGeneration;
        status.setText("Loading " + season.title() + "…");
        content.getChildren().setAll(loadingCard("Loading season…"));
        app.anilist().details(season.mediaId(), MediaType.ANIME).whenComplete((media,error) -> Platform.runLater(() -> {
            if (error != null) {
                status.setText("Season load failed: " + root(error));
                content.getChildren().setAll(errorCard(root(error)));
                return;
            }
            currentMedia = media;
            status.setText(media.title());
            content.getChildren().setAll(detailsView(media));
        }));
    }

    private Node mediaInfo(AniMedia media) {
        VBox card = new VBox(12); card.getStyleClass().add("info-card"); card.setPadding(new Insets(18));
        Label kicker = new Label("ARCHIVE INDEX"); kicker.getStyleClass().add("section-kicker");
        Label heading = new Label("Story details"); heading.getStyleClass().add("section-title");
        FlowPane facts = new FlowPane(8,8);
        facts.getChildren().addAll(
                infoTile("FORMAT", safe(media.format())),
                infoTile("STATUS", safe(media.releaseStatus())),
                infoTile(media.type()==MediaType.MANGA?"CHAPTERS":"EPISODES", safe(media.type()==MediaType.MANGA?media.totalChapters():media.totalEpisodes())),
                infoTile("SCORE", media.averageScore()==null?"—":media.averageScore()+"%"),
                infoTile("SEASON", (safe(media.season())+" "+safe(media.seasonYear())).trim()),
                infoTile("SOURCE", safe(media.sourceMaterial())),
                infoTile("STUDIO", String.join(", ",media.studios())),
                infoTile("RELEASED", safe(media.startDate()))
        );
        card.getChildren().addAll(kicker,heading,facts,listTrackingPanel(media));
        return card;
    }

    private Node infoTile(String name, String value) {
        Label label = new Label(name); label.getStyleClass().add("fact-label");
        Label content = new Label(value==null||value.isBlank()?"—":value); content.setWrapText(true); content.getStyleClass().add("fact-value");
        VBox tile = new VBox(4,label,content); tile.setPrefWidth(150); tile.setMinHeight(58); tile.getStyleClass().add("fact-tile");
        return tile;
    }

    private Node officialStreamingPanel(AniMedia media) {
        VBox card = new VBox(11); card.getStyleClass().add("info-card"); card.setPadding(new Insets(16));
        Label heading = new Label("Official streaming availability"); heading.getStyleClass().add("section-title");
        Label note = new Label("Availability is supplied by AniList's legal streaming/external-link metadata. Protected services open in their official site/app rather than being treated as Aokuvue playback sources.");
        note.setWrapText(true); note.getStyleClass().add("source-status");
        card.getChildren().addAll(heading, note);

        if (!media.streamingServices().isEmpty()) {
            Label servicesLabel = new Label("Services"); servicesLabel.getStyleClass().add("field-label");
            FlowPane services = new FlowPane(8,8); services.setAlignment(Pos.CENTER_LEFT);
            for (StreamingServiceLink link : media.streamingServices()) {
                Button b = new Button(link.site()); b.getStyleClass().add("secondary-button");
                String tip = String.join(" · ", List.of(
                        link.language()==null?"":link.language(),
                        link.notes()==null?"":link.notes()
                ).stream().filter(x->!x.isBlank()).toList());
                if (!tip.isBlank()) b.setTooltip(new Tooltip(tip));
                b.setOnAction(e -> openUri(link.uri()));
                services.getChildren().add(b);
            }
            card.getChildren().addAll(servicesLabel, services);
        }

        if (!media.streamingEpisodes().isEmpty()) {
            Label episodesLabel = new Label("Official episode links"); episodesLabel.getStyleClass().add("field-label");
            FlowPane episodeLinks = new FlowPane(8,8); episodeLinks.setAlignment(Pos.CENTER_LEFT);
            for (StreamingEpisodeLink link : media.streamingEpisodes()) {
                String label = link.site() == null || link.site().isBlank()
                        ? link.title()
                        : link.title() + " · " + link.site();
                Button b = new Button(trim(label, 52)); b.getStyleClass().add("text-button");
                b.setTooltip(new Tooltip(label)); b.setOnAction(e -> openUri(link.uri()));
                episodeLinks.getChildren().add(b);
            }
            card.getChildren().addAll(episodesLabel, episodeLinks);
        }
        return card;
    }

    private Node listTrackingPanel(AniMedia media) {
        HBox row = new HBox(9); row.setAlignment(Pos.CENTER_LEFT); row.getStyleClass().add("tracking-row");
        Label label = new Label("AniList"); label.getStyleClass().add("field-label");
        ComboBox<MediaListStatus> statusBox = new ComboBox<>(FXCollections.observableArrayList(MediaListStatus.values()));
        statusBox.setValue(media.listEntry().status()==null?MediaListStatus.PLANNING:media.listEntry().status());
        Integer knownTotal=media.type()==MediaType.MANGA?media.totalChapters():media.totalEpisodes();
        Spinner<Integer> progress = new Spinner<>(0, Math.max(10000, knownTotal==null?10000:knownTotal), media.listEntry().progress());
        progress.setEditable(true); progress.setPrefWidth(100);
        Button save = new Button(media.listEntry().onList()?"Update AniList":"Add to AniList"); save.getStyleClass().add("primary-button");
        save.setDisable(!app.anilist().authenticated());
        save.setOnAction(e -> {
            UserListEntry x=media.listEntry(); MediaListStatus st=statusBox.getValue(); int p=progress.getValue();
            if(st==MediaListStatus.COMPLETED && knownTotal!=null) p=knownTotal;
            status.setText("Updating AniList…");
            app.listService().save(media,st,p,x.score(),x.repeat(),x.priority(),x.notes(),x.privateEntry(),x.customLists(),x.startedAt(),
                    st==MediaListStatus.COMPLETED&&x.completedAt()==null?LocalDate.now():x.completedAt())
                    .whenComplete((updated,error)->Platform.runLater(()->{
                        if(error!=null) status.setText("AniList update failed: "+root(error));
                        else { status.setText("AniList updated: "+updated.status()+" · "+updated.progress()); refreshLists(); }
                    }));
        });
        row.getChildren().addAll(label,statusBox,new Label(media.type()==MediaType.MANGA?"Chapter":"Episode"),progress,save);
        return row;
    }

    private Node episodeSection(AniMedia media, SourceRecommendationView recommendation) {
        VBox section = new VBox(12); section.getStyleClass().add("episode-section"); section.setPadding(new Insets(18));
        Label kicker = new Label("WATCH IN AOKUVUE"); kicker.getStyleClass().add("section-kicker");
        Label heading = new Label("Episodes"); heading.getStyleClass().add("section-title");
        List<AnimeSource> visibleSources = app.sources().configuredUserVisibleSources();
        if (visibleSources.isEmpty()) {
            Label unavailable = new Label("Choose an EverythingMoe source, then browse and play its episodes inside Aokuvue. Availability and player controls are provided by the selected website.");
            unavailable.setWrapText(true);
            unavailable.getStyleClass().add("source-status");
            ComboBox<ProviderSite> externalSources=new ComboBox<>(FXCollections.observableArrayList(app.providerDirectory().latestSnapshot()));
            externalSources.setPrefWidth(280);
            if(!externalSources.getItems().isEmpty())externalSources.getSelectionModel().selectFirst();
            Button openSource = new Button("Watch in app");
            openSource.getStyleClass().add("primary-button");
            openSource.disableProperty().bind(externalSources.valueProperty().isNull());
            openSource.setOnAction(e->{ProviderSite selected=externalSources.getValue();if(selected!=null)showEverythingMoePlayer(media,selected);});
            Button refreshSources = new Button("Refresh rankings");
            refreshSources.getStyleClass().add("secondary-button");
            Runnable refresh=()->{refreshSources.setDisable(true);app.providerDirectory().refresh().whenComplete((sites,error)->Platform.runLater(()->{refreshSources.setDisable(false);List<ProviderSite> resolved=sites==null?app.providerDirectory().fallbackSnapshot():sites;externalSources.setItems(FXCollections.observableArrayList(resolved));if(!resolved.isEmpty())externalSources.getSelectionModel().selectFirst();showRecommendationChecking(recommendation,null);status.setText(error==null?"EverythingMoe rankings refreshed.":"Using cached EverythingMoe rankings.");}));};
            refreshSources.setOnAction(e->refresh.run());
            FlowPane controls=new FlowPane(8,8,new Label("External source"),externalSources,openSource,refreshSources);controls.setAlignment(Pos.CENTER_LEFT);
            section.getChildren().addAll(kicker, heading, controls, unavailable);
            Platform.runLater(refresh);
            return section;
        }
        ComboBox<AnimeSource> sourceBox = new ComboBox<>(FXCollections.observableArrayList(visibleSources));
        sourceBox.setCellFactory(x -> sourceCell()); sourceBox.setButtonCell(sourceCell()); sourceBox.setPrefWidth(240);
        SourceSelection selection = app.episodes().selectionFor(media);
        AnimeSource preferredSource = preferredSourceFor(media, visibleSources);
        if (preferredSource != null) sourceBox.setValue(preferredSource);
        if (sourceBox.getValue() == null && !visibleSources.isEmpty()) sourceBox.setValue(visibleSources.get(0));
        Button refresh = new Button("Refresh episodes"); refresh.getStyleClass().add("secondary-button");
        Button rematch = new Button("Rematch source"); rematch.getStyleClass().add("secondary-button");
        Button chooseMatch = new Button("Choose match"); chooseMatch.getStyleClass().add("secondary-button");
        ComboBox<PlaybackLanguage> languageBox = new ComboBox<>(FXCollections.observableArrayList(PlaybackLanguage.values()));
        languageBox.setValue(selection.languagePreference());
        languageBox.setPrefWidth(145);
        languageBox.setTooltip(new Tooltip("Auto keeps the provider default. English Sub prefers subtitled variants and English subtitle tracks. English Dub prefers English-audio variants."));
        Label loadState = new Label(); loadState.getStyleClass().add("source-status");
        FlowPane controls = new FlowPane(8,8,new Label("Playback source"),sourceBox,new Label("Language"),languageBox,refresh,rematch,chooseMatch,loadState); controls.setAlignment(Pos.CENTER_LEFT);
        boolean hasPlaybackSources = !visibleSources.isEmpty();
        sourceBox.setDisable(!hasPlaybackSources); rematch.setDisable(!hasPlaybackSources); chooseMatch.setDisable(!hasPlaybackSources);
        Label playbackHint = new Label(); playbackHint.getStyleClass().add("source-status"); playbackHint.setWrapText(true);
        if (!hasPlaybackSources) {
            playbackHint.setText("No direct playback provider is configured. Episodes are indexed internally for AniList tracking; AniList itself is not a playback source.");
        } else {
            playbackHint.setText(visibleSources.size()+" direct playback source(s) configured. If the selected provider cannot match this title, Aokuvue automatically tries the other configured playback sources before using the internal episode index.");
        }

        ListView<SourceEpisode> episodes = new ListView<>(); episodes.setOrientation(javafx.geometry.Orientation.HORIZONTAL);episodes.setPrefHeight(245); episodes.getStyleClass().add("episode-list");
        currentEpisodeList = episodes;
        episodes.setCellFactory(v -> episodeCell(media));
        section.getChildren().addAll(kicker,heading,controls,playbackHint,episodes);
        VBox.setVgrow(episodes,Priority.ALWAYS);

        Runnable load = () -> loadEpisodesInto(media, sourceBox.getValue(), false, episodes, loadState, sourceBox, recommendation);
        sourceBox.setOnAction(e -> { if(suppressSourceChange)return; AnimeSource s=sourceBox.getValue(); showRecommendationChecking(recommendation,s); loadEpisodesInto(media,s,false,episodes,loadState,sourceBox,recommendation); });
        languageBox.setOnAction(e -> {
            PlaybackLanguage language = languageBox.getValue() == null ? PlaybackLanguage.AUTO : languageBox.getValue();
            AnimeSource selectedSource = sourceBox.getValue();
            app.episodes().setLanguagePreference(media, language);
            loadEpisodesInto(media, selectedSource, false, episodes, loadState, sourceBox, recommendation);
        });
        refresh.setOnAction(e -> loadEpisodesInto(media,sourceBox.getValue(),false,episodes,loadState,sourceBox,recommendation));
        rematch.setOnAction(e -> { AnimeSource s=sourceBox.getValue(); if(s!=null) loadEpisodesInto(media,s,true,episodes,loadState,sourceBox,recommendation); });
        chooseMatch.setOnAction(e -> { AnimeSource s=sourceBox.getValue(); if(s!=null) chooseSourceMatch(media,s,episodes,loadState,sourceBox,recommendation); });
        Platform.runLater(load);
        return section;
    }

    private ListCell<AnimeSource> sourceCell() {
        return new ListCell<>() {
            @Override protected void updateItem(AnimeSource item, boolean empty) {
                super.updateItem(item,empty); if(empty||item==null){setText(null);return;}
                setText(item.descriptor().name());
            }
        };
    }

    private void loadEpisodesInto(AniMedia media, AnimeSource source, boolean rematch, ListView<SourceEpisode> list, Label stateLabel, ComboBox<AnimeSource> sourceBox, SourceRecommendationView recommendation) {
        long generation = ++episodeLoadGeneration;
        list.getItems().clear(); currentEpisodeLoad=null;
        showRecommendationChecking(recommendation, source);
        stateLabel.setText(rematch?"Rematching…":"Loading…");
        String sourceName = source == null ? "episode index" : source.descriptor().name();
        status.setText("Loading "+sourceName+" episodes for "+media.title()+"…");
        CompletableFuture<EpisodeLoadResult> future = source == null
                ? app.episodes().loadEpisodes(media, rematch)
                : app.episodes().loadEpisodes(media,source.descriptor().id(),rematch);
        future.whenComplete((loaded,error)->Platform.runLater(()->{
            if(generation != episodeLoadGeneration) return;
            if(error!=null){stateLabel.setText("Failed");status.setText(root(error));recommendation.eyebrow.setText("SOURCE STATUS");recommendation.detail.setText("This source could not confirm a safe match for the selected series.");recommendation.badge.setText("NOT CONFIRMED");return;}
            if(currentMedia==null||currentMedia.id()!=media.id())return;
            currentEpisodeLoad=loaded;
            list.setItems(FXCollections.observableArrayList(loaded.episodes()));
            boolean internalIndex = "tracking".equals(loaded.source().descriptor().id());
            boolean playbackFallback = source != null && !internalIndex
                    && !loaded.source().descriptor().id().equals(source.descriptor().id());
            boolean fallback = source != null && internalIndex;
            if (playbackFallback) {
                suppressSourceChange=true;
                try { sourceBox.setValue(loaded.source()); } finally { suppressSourceChange=false; }
                stateLabel.setText(loaded.episodes().size()+" episodes · auto-switched to "+loaded.source().descriptor().name());
                status.setText(source.descriptor().name()+" had no usable match; using "+loaded.source().descriptor().name()+" instead.");
            } else if (fallback) {
                stateLabel.setText(loaded.episodes().size()+" episodes · internal index · lazy playback enabled");
                status.setText("Provider indexing did not match immediately. Watch remains available and will resolve the selected episode lazily through configured sources.");
            } else if (internalIndex) {
                stateLabel.setText(loaded.episodes().size()+" episodes · tracking index");
                status.setText("Episode index loaded for AniList tracking. Configure a playback source to watch in-app.");
            } else {
                stateLabel.setText(loaded.episodes().size()+" episodes · matched "+loaded.series().name());
                status.setText(loaded.source().descriptor().name()+" · "+loaded.episodes().size()+" episodes");
            }
            showRecommendationVerified(recommendation, loaded);
            list.refresh();
        }));
    }

    private void chooseSourceMatch(AniMedia media, AnimeSource source, ListView<SourceEpisode> list, Label stateLabel, ComboBox<AnimeSource> sourceBox, SourceRecommendationView recommendation) {
        long generation = ++episodeLoadGeneration;
        stateLabel.setText("Searching source…");
        status.setText("Searching " + source.descriptor().name() + " for " + media.title() + "…");
        source.search(media).whenComplete((matches,error)->Platform.runLater(()->{
            if(generation != episodeLoadGeneration) return;
            if(error!=null){stateLabel.setText("Search failed");status.setText(root(error));return;}
            List<SourceSeries> ranked=TitleMatcher.ranked(media,matches==null?List.of():matches);
            if(ranked.isEmpty()){stateLabel.setText("No matches");status.setText("No source matches found.");return;}
            List<String> options=ranked.stream().map(s->s.name()+(s.total()==null?"":" · "+s.total()+" episodes")+" · "+s.seriesId()).toList();
            ChoiceDialog<String> dialog=new ChoiceDialog<>(options.get(0),options);
            dialog.setTitle("Choose source match");
            dialog.setHeaderText(media.title()+" · "+source.descriptor().name());
            dialog.setContentText("Matched series:");
            dialog.showAndWait().ifPresent(choice->{
                int selectedIndex=options.indexOf(choice);
                if(selectedIndex<0)return;
                SourceSeries series=ranked.get(selectedIndex);
                app.episodes().overrideSeriesMatch(media,series);
                loadEpisodesInto(media,source,false,list,stateLabel,sourceBox,recommendation);
            });
        }));
    }

    private ListCell<SourceEpisode> episodeCell(AniMedia media) {
        return new ListCell<>() {
            @Override protected void updateItem(SourceEpisode ep, boolean empty) {
                super.updateItem(ep,empty); if(empty||ep==null){setGraphic(null);setText(null);return;}
                Label num=new Label("EP "+ep.number());num.getStyleClass().add("episode-number");
                Label title=new Label(ep.title()==null||ep.title().isBlank()?"Episode "+ep.number():ep.title());title.setWrapText(true);title.getStyleClass().add("episode-title");
                Label flags=new Label((ep.group()==null?"":ep.group())+(ep.filler()?" · FILLER":""));flags.getStyleClass().add("episode-meta");
                String artUrl=media.bannerImage()==null||media.bannerImage().isBlank()?media.coverImage():media.bannerImage();ImageView art=coverImage(artUrl,210,112);art.getStyleClass().add("episode-art");StackPane artwork=new StackPane(art,num);StackPane.setAlignment(num,Pos.TOP_LEFT);StackPane.setMargin(num,new Insets(8));
                VBox info=new VBox(4,title,flags);HBox.setHgrow(info,Priority.ALWAYS);
                Button mark=new Button("Mark watched");mark.getStyleClass().add("secondary-button");mark.setOnAction(e->markWatched(media,ep));
                boolean playable=currentEpisodeLoad!=null&&(currentEpisodeLoad.source().descriptor().capabilities().directPlayback()
                        || !app.sources().playbackSources().isEmpty());
                Button watch=new Button(playable?"▶  Play":"Unavailable");watch.getStyleClass().add("primary-button");
                watch.setDisable(!playable); watch.setOnAction(e->resolveAndPlay(media,ep));
                HBox actions=new HBox(7,watch,mark);actions.setAlignment(Pos.CENTER_LEFT);
                VBox row=new VBox(7,artwork,info,actions);row.setPrefWidth(220);row.setMaxWidth(220);row.setPadding(new Insets(6));row.getStyleClass().add("episode-row");
                setGraphic(row);setText(null);
            }
        };
    }

    private void markWatched(AniMedia media, SourceEpisode ep) {
        if(!app.anilist().authenticated()){status.setText("Login to AniList to update progress.");return;}
        int n;try{n=(int)Math.floor(ep.numericNumber());}catch(Exception x){return;}
        if(n<1)return;status.setText("Updating AniList to Episode "+n+"…");
        app.listService().setProgress(media,n).whenComplete((entry,error)->Platform.runLater(()->status.setText(error==null?"AniList progress: "+entry.progress():"AniList sync failed: "+root(error))));
    }

    private void resolveAndPlay(AniMedia media, SourceEpisode ep) {
        if(currentEpisodeLoad==null)return;
        String resolverName=currentEpisodeLoad.source().descriptor().capabilities().directPlayback()
                ? currentEpisodeLoad.source().descriptor().name()
                : app.episodes().selectionFor(media).sourceId();
        status.setText("Resolving Episode "+ep.number()+" through "+resolverName+"…");
        app.episodes().resolveEpisode(media,currentEpisodeLoad,ep).whenComplete((resolved,error)->Platform.runLater(()->{
            if(error!=null){
                String reason=root(error);
                System.err.println("[Aokuvue][Playback] resolve failed"
                        +" mediaId="+media.id()+" episode="+ep.number()
                        +" source="+currentEpisodeLoad.source().descriptor().id()
                        +" reason="+reason);
                status.setText("Playback source failed: "+reason);return;
            }
            System.out.println("[Aokuvue][Playback] resolved"
                    +" mediaId="+media.id()+" episode="+ep.number()
                    +" source="+resolved.source().descriptor().id()
                    +" server="+resolved.resolved().server().name()
                    +" videos="+resolved.resolved().videos().size()
                    +" subtitles="+resolved.resolved().subtitles().size());
            showPlayer(new PlayerSession(media,currentEpisodeLoad,resolved));
        }));
    }

    private void showPlayer(PlayerSession session) {
        if ("embed".equalsIgnoreCase(session.playback().selectedVideo().container())) {
            showEmbeddedEpisodePlayer(session);
            return;
        }
        playerLoadGeneration++;
        page=Page.PLAYER;updateNav();
        BorderPane pane=new BorderPane();pane.getStyleClass().add("player-screen");
        Button back=new Button("‹ Back to details");back.getStyleClass().add("secondary-button");back.setOnAction(e->{playerLoadGeneration++;exitPlayerFullScreen();app.player().close();page=Page.DETAILS;content.getChildren().setAll(detailsView(session.media()));});
        Label title=new Label(session.media().title()+" · Episode "+session.playback().episode().number());title.getStyleClass().add("player-title");
        Label serverName=new Label("Server: "+session.playback().resolved().server().name());serverName.getStyleClass().add("player-meta");
        Button serverButton=new Button("Change server");serverButton.getStyleClass().add("secondary-button");serverButton.setOnAction(e->choosePlayerServer(session));
        Region spacer=new Region();HBox.setHgrow(spacer,Priority.ALWAYS);
        Button mark=new Button("Mark watched");mark.getStyleClass().add("primary-button");
        mark.setOnAction(e->app.player().markWatchedNow());
        HBox top=new HBox(12,back,title,serverName,serverButton,spacer,mark);top.setAlignment(Pos.CENTER_LEFT);top.setPadding(new Insets(10,14,10,14));top.setMinHeight(Region.USE_PREF_SIZE);top.setMaxHeight(Region.USE_PREF_SIZE);top.getStyleClass().add("player-top");

        MediaView view=new MediaView();view.setPreserveRatio(true);
        Label subtitleOverlay=new Label();subtitleOverlay.getStyleClass().add("subtitle-overlay");subtitleOverlay.setStyle("-fx-font-size: "+app.player().settings().subtitleSize()+"px;");subtitleOverlay.setWrapText(true);subtitleOverlay.setMaxWidth(900);subtitleOverlay.setMinHeight(Region.USE_PREF_SIZE);subtitleOverlay.setMaxHeight(Region.USE_PREF_SIZE);subtitleOverlay.setMouseTransparent(true);subtitleOverlay.setVisible(false);
        StackPane video=new StackPane(view,subtitleOverlay,top);video.getStyleClass().add("video-stage");video.setMinSize(0,0);pane.setMinSize(0,0);view.fitWidthProperty().bind(video.widthProperty());view.fitHeightProperty().bind(video.heightProperty());StackPane.setAlignment(subtitleOverlay,Pos.BOTTOM_CENTER);StackPane.setMargin(subtitleOverlay,new Insets(0,48,30,48));StackPane.setAlignment(top,Pos.TOP_CENTER);pane.setCenter(video);

        Button prev=new Button("│◀");Button rewind=new Button("−"+app.player().settings().seekSeconds()+"s");Button play=new Button("▶");play.getStyleClass().add("player-primary-control");Button forward=new Button("+"+app.player().settings().seekSeconds()+"s");Button next=new Button("▶│");
        Slider seek=new Slider(0,1,0);HBox.setHgrow(seek,Priority.ALWAYS);Label time=new Label("00:00 / 00:00");Slider volume=new Slider(0,1,0.8);volume.setPrefWidth(100);
        PlaybackLanguage playerLanguage=app.episodes().selectionFor(session.media()).languagePreference();
        List<PlaybackSource> qualityVideos=EpisodeLanguageSelector.rankVideos(session.playback().resolved().videos(),playerLanguage);
        ComboBox<String> quality=new ComboBox<>(FXCollections.observableArrayList(qualityVideos.stream().map(MainWindow::videoLabel).toList()));quality.setPrefWidth(190);
        int qualityIndex=Math.max(0,Math.min(session.playback().selectedVideoIndex(),Math.max(0,qualityVideos.size()-1)));if(!qualityVideos.isEmpty())quality.getSelectionModel().select(qualityIndex);
        List<SubtitleTrack> subtitleTracks=session.playback().resolved().subtitles();
        List<String> subtitleNames=new ArrayList<>();subtitleNames.add("Off");
        for(int i=0;i<subtitleTracks.size();i++){SubtitleTrack track=subtitleTracks.get(i);String lang=track.language()==null||track.language().isBlank()?"Subtitle "+(i+1):track.language();subtitleNames.add(lang+(track.defaultTrack()?" · default":""));}
        ComboBox<String> subtitles=new ComboBox<>(FXCollections.observableArrayList(subtitleNames));subtitles.setPrefWidth(130);
        int selectedSub=session.playback().selectedSubtitleIndex()==null?0:Math.min(subtitleTracks.size(),session.playback().selectedSubtitleIndex()+1);subtitles.getSelectionModel().select(selectedSub);
        ComboBox<Double> speed=new ComboBox<>(FXCollections.observableArrayList(0.5,0.75,1.0,1.25,1.5,1.75,2.0));double configuredSpeed=app.player().settings().defaultSpeed();speed.setValue(speed.getItems().stream().min(Comparator.comparingDouble(v->Math.abs(v-configuredSpeed))).orElse(1.0));speed.setPrefWidth(75);
        Button full=new Button("⛶");full.setTooltip(new Tooltip("Fullscreen"));
        rewind.setOnAction(e->app.player().seekRelative(-app.player().settings().seekSeconds()));forward.setOnAction(e->app.player().seekRelative(app.player().settings().seekSeconds()));play.setOnAction(e->app.player().playPause());
        prev.setOnAction(e->playAdjacent(session,-1));next.setOnAction(e->playAdjacent(session,1));
        quality.setOnAction(e->{int idx=quality.getSelectionModel().getSelectedIndex();if(idx<0||idx>=qualityVideos.size()||idx==session.playback().selectedVideoIndex())return;app.episodes().setPreferredQuality(session.media(),qualityVideos.get(idx).quality(),idx);PlaybackResolution changed=new PlaybackResolution(session.playback().source(),session.playback().series(),session.playback().episode(),session.playback().resolved(),qualityVideos.get(idx),idx,session.playback().selectedSubtitleIndex());showPlayer(new PlayerSession(session.media(),session.episodeLoad(),changed));});
        HBox timeline=new HBox(12,time,seek);timeline.setAlignment(Pos.CENTER_LEFT);HBox.setHgrow(seek,Priority.ALWAYS);timeline.getStyleClass().add("player-timeline");
        String posterUrl=session.media().bannerImage()==null||session.media().bannerImage().isBlank()?session.media().coverImage():session.media().bannerImage();ImageView thumb=coverImage(posterUrl,145,88);thumb.getStyleClass().add("player-cover");
        Label eyebrow=new Label("A O K U V U E   S E L E C T I O N");eyebrow.getStyleClass().add("section-kicker");Label mediaTitle=new Label(session.media().title());mediaTitle.getStyleClass().add("player-media-title");Label episodeLabel=new Label("Episode "+session.playback().episode().number()+"  ·  "+safe(session.playback().episode().title()));episodeLabel.getStyleClass().add("player-meta");VBox mediaCopy=new VBox(4,eyebrow,mediaTitle,episodeLabel);mediaCopy.setPrefWidth(300);
        HBox transport=new HBox(8,prev,rewind,play,forward,next);transport.setAlignment(Pos.CENTER);transport.setMaxWidth(Region.USE_PREF_SIZE);
        HBox selectors=new HBox(8,new Label("CC"),subtitles,quality,new Label("Speed"),speed,new Label("Vol"),volume,full);selectors.setAlignment(Pos.CENTER_RIGHT);selectors.setMaxWidth(Region.USE_PREF_SIZE);
        HBox mediaIdentity=new HBox(18,thumb,mediaCopy);mediaIdentity.setAlignment(Pos.CENTER_LEFT);mediaIdentity.setMaxWidth(Region.USE_PREF_SIZE);
        StackPane controlsRow=new StackPane(mediaIdentity,transport,selectors);
        StackPane.setAlignment(mediaIdentity,Pos.CENTER_LEFT);StackPane.setAlignment(transport,Pos.CENTER);StackPane.setAlignment(selectors,Pos.CENTER_RIGHT);
        VBox bottom=new VBox(9,timeline,controlsRow);bottom.setPadding(new Insets(9,22,14,22));bottom.getStyleClass().add("player-controls");pane.setBottom(bottom);
        content.getChildren().setAll(pane);
        configurePlayerFullScreen(pane,null,bottom,full);
        try {
            app.player().setEndOfMediaAction(()->{
                if(app.player().settings().autoNextEpisode())Platform.runLater(()->playAdjacent(session,1));
            });
            MediaPlayer mp=app.player().load(session);
            view.setMediaPlayer(mp);volume.valueProperty().addListener((o,a,b)->mp.setVolume(b.doubleValue()));mp.setVolume(volume.getValue());speed.setOnAction(e->mp.setRate(speed.getValue()==null?1.0:speed.getValue()));mp.setRate(speed.getValue()==null?1.0:speed.getValue());
            final List<SubtitleCue>[] cues=new List[]{List.of()};
            java.util.function.Consumer<Integer> selectSubtitle=subtitleIndex->{
                cues[0]=List.of();subtitleOverlay.setText("");subtitleOverlay.setVisible(false);
                if(subtitleIndex==null||subtitleIndex<0||subtitleIndex>=subtitleTracks.size()){status.setText("Subtitles off.");return;}
                SubtitleTrack track=subtitleTracks.get(subtitleIndex);loadSubtitleTrack(track).whenComplete((loaded,error)->Platform.runLater(()->{if(error!=null){status.setText("Subtitle load failed: "+root(error));return;}cues[0]=loaded;status.setText((track.language()==null||track.language().isBlank()?"Subtitle":track.language()+" subtitles")+" loaded ("+loaded.size()+" cues).");}));
            };
            subtitles.setOnAction(e->{int uiIndex=subtitles.getSelectionModel().getSelectedIndex();Integer idx=uiIndex<=0?null:uiIndex-1;app.episodes().setSubtitleIndex(session.media(),idx);selectSubtitle.accept(idx);});
            selectSubtitle.accept(session.playback().selectedSubtitleIndex());
            final boolean[] dragging={false};seek.setOnMousePressed(e->dragging[0]=true);seek.setOnMouseReleased(e->{dragging[0]=false;if(mp.getTotalDuration().toMillis()>0)mp.seek(Duration.millis(seek.getValue()*mp.getTotalDuration().toMillis()));});
            final boolean[] introSkipped={false},outroSkipped={false};
            mp.currentTimeProperty().addListener((o,a,b)->{
                double position=b.toMillis();SkipInterval intro=session.playback().resolved().intro(),outro=session.playback().resolved().outro();
                if(!introSkipped[0]&&app.player().settings().autoSkipIntro()&&intro!=null&&intro.contains(position)){introSkipped[0]=true;mp.seek(Duration.millis(intro.endMs()));status.setText("Skipped detected intro.");return;}
                if(!outroSkipped[0]&&app.player().settings().autoSkipOutro()&&outro!=null&&outro.contains(position)){outroSkipped[0]=true;mp.seek(Duration.millis(outro.endMs()));status.setText("Skipped detected outro.");return;}
                if(!dragging[0]&&mp.getTotalDuration().toMillis()>0)seek.setValue(position/mp.getTotalDuration().toMillis());time.setText(clock(position)+" / "+clock(mp.getTotalDuration().toMillis()));String caption=SubtitleParser.at(cues[0],(long)position);subtitleOverlay.setText(caption);subtitleOverlay.setVisible(!caption.isBlank());
            });
        } catch(Exception ex){
            System.err.println("[Aokuvue][Player] startup failed: "+root(ex));
            status.setText("Player failed: "+root(ex));
        }
    }

    private void configurePlayerFullScreen(BorderPane playerPane,Node playerTop,Node playerControls,Button fullScreenButton) {
        if(getScene()==null||!(getScene().getWindow() instanceof Stage stage))return;
        if(playerFullScreenRestore!=null)playerFullScreenRestore.run();
        detachPlayerFullScreenListener();
        playerFullScreenStage=stage;
        boolean[] fullScreenUi={false};
        PauseTransition hideControls=new PauseTransition(Duration.seconds(2.5));
        Runnable showControls=()->{
            if(!fullScreenUi[0])return;
            playerControls.setOpacity(1.0);playerControls.setMouseTransparent(false);
            hideControls.playFromStart();
        };
        hideControls.setOnFinished(event->{
            if(fullScreenUi[0]){playerControls.setOpacity(0.0);playerControls.setMouseTransparent(true);}
        });
        playerControls.setOnMouseEntered(event->{if(fullScreenUi[0]){playerControls.setOpacity(1.0);playerControls.setMouseTransparent(false);hideControls.stop();}});
        playerControls.setOnMouseExited(event->{if(fullScreenUi[0])hideControls.playFromStart();});
        playerPane.setOnMouseMoved(event->{
            if(!fullScreenUi[0])return;
            if(event.getY()>=playerPane.getHeight()-110){
                playerControls.setOpacity(1.0);playerControls.setMouseTransparent(false);hideControls.stop();
            } else if(playerControls.getOpacity()>0.01) hideControls.playFromStart();
        });
        java.util.function.Consumer<Boolean> applyFullScreenUi=isFullScreen->{
            fullScreenUi[0]=isFullScreen;
            setTop(isFullScreen?null:applicationTopBar);
            setBottom(isFullScreen?null:applicationStatusBar);
            applicationShell.setLeft(isFullScreen?null:applicationSidebar);
            applicationWorkspace.setTop(isFullScreen?null:applicationHeader);
            playerPane.setTop(isFullScreen?null:playerTop);
            fullScreenButton.setText(isFullScreen?"⤢":"⛶");
            if(isFullScreen){
                if(!playerPane.getStyleClass().contains("player-fullscreen"))playerPane.getStyleClass().add("player-fullscreen");
                showControls.run();
            } else {
                hideControls.stop();
                playerPane.getStyleClass().remove("player-fullscreen");
                playerControls.setOpacity(1.0);playerControls.setMouseTransparent(false);
                playerControls.setVisible(true);playerControls.setManaged(true);
                playerPane.requestLayout();
            }
        };
        playerFullScreenRestore=()->applyFullScreenUi.accept(false);
        playerFullScreenListener=(observable,wasFullScreen,isFullScreen)->{
            applyFullScreenUi.accept(isFullScreen);
            if(!isFullScreen)Platform.runLater(()->applyFullScreenUi.accept(false));
        };
        stage.fullScreenProperty().addListener(playerFullScreenListener);
        stage.setFullScreenExitHint("Press Esc or use Exit fullscreen");
        fullScreenButton.setOnAction(event->{
            boolean enterFullScreen=!stage.isFullScreen();
            stage.setFullScreen(enterFullScreen);
            if(!enterFullScreen){
                applyFullScreenUi.accept(false);
                Platform.runLater(()->applyFullScreenUi.accept(false));
            }
        });
        applyFullScreenUi.accept(stage.isFullScreen());
    }

    private void exitPlayerFullScreen() {
        if(playerFullScreenStage!=null&&playerFullScreenStage.isFullScreen())playerFullScreenStage.setFullScreen(false);
        if(playerFullScreenRestore!=null)playerFullScreenRestore.run();
        detachPlayerFullScreenListener();
    }

    private void detachPlayerFullScreenListener() {
        if(playerFullScreenStage!=null&&playerFullScreenListener!=null){
            playerFullScreenStage.fullScreenProperty().removeListener(playerFullScreenListener);
        }
        playerFullScreenStage=null;playerFullScreenListener=null;
        playerFullScreenRestore=null;
    }

    private void showEmbeddedEpisodePlayer(PlayerSession session) {
        playerLoadGeneration++;
        app.player().close();
        app.playbackProgress().touch(
                session.media().id(), session.playback().source().descriptor().id(),
                session.playback().episode().number());
        page=Page.PLAYER; updateNav();

        BorderPane pane=new BorderPane(); pane.getStyleClass().add("player-screen");
        WebView web=new WebView(); web.setContextMenuEnabled(false);
        WebEngine engine=web.getEngine(); engine.setJavaScriptEnabled(true);
        engine.setCreatePopupHandler(features->null);

        Button back=new Button("‹ Back to episodes"); back.getStyleClass().add("secondary-button");
        back.setOnAction(e->{engine.load("about:blank");page=Page.DETAILS;content.getChildren().setAll(detailsView(session.media()));});
        Label title=new Label(session.media().title()+" · Episode "+session.playback().episode().number());
        title.getStyleClass().add("player-title");
        Label server=new Label("Server: "+session.playback().resolved().server().name()); server.getStyleClass().add("player-meta");
        Button changeServer=new Button("Change server"); changeServer.getStyleClass().add("secondary-button");
        changeServer.setOnAction(e->choosePlayerServer(session));
        Region spacer=new Region(); HBox.setHgrow(spacer,Priority.ALWAYS);
        Button watched=new Button("Mark watched"); watched.getStyleClass().add("primary-button");
        watched.setOnAction(e->{
            app.playbackProgress().markWatched(session.media().id(),session.playback().source().descriptor().id(),session.playback().episode().number());
            markWatched(session.media(),session.playback().episode());
            watched.setText("Watched ✓"); watched.setDisable(true);
        });
        HBox top=new HBox(12,back,title,server,changeServer,spacer,watched);
        top.setAlignment(Pos.CENTER_LEFT); top.setPadding(new Insets(10,14,10,14)); top.getStyleClass().add("player-top");
        pane.setTop(top); pane.setCenter(web); content.getChildren().setAll(pane);

        URI uri=session.playback().selectedVideo().uri();
        status.setText("Opening Episode "+session.playback().episode().number()+" in the Aokuvue player…");
        engine.getLoadWorker().stateProperty().addListener((obs,oldState,newState)->{
            if(newState==Worker.State.SUCCEEDED)status.setText("Playing Episode "+session.playback().episode().number()+".");
            else if(newState==Worker.State.FAILED){Throwable error=engine.getLoadWorker().getException();status.setText("Player failed: "+(error==null?"unknown error":root(error)));}
        });
        engine.load(uri.toString());
    }

    private void showMangaReader(AniMedia media) {
        if (media == null || media.type() != MediaType.MANGA) return;
        playerLoadGeneration++;
        app.player().close();
        page = Page.READER;
        updateNav();
        MangaReaderPane reader = new MangaReaderPane(app.http(), media, () -> {
            page = Page.DETAILS;
            updateNav();
            content.getChildren().setAll(detailsView(media));
            status.setText(media.title());
        }, text -> Platform.runLater(() -> status.setText(text)));
        content.getChildren().setAll(reader);
        status.setText("Opening " + media.title() + " in the native manga reader…");
        Platform.runLater(reader::requestFocus);
    }

    private void showEverythingMoePlayer(AniMedia media, ProviderSite source) {
        if(media==null||source==null||source.baseUri()==null)return;
        playerLoadGeneration++;
        app.player().close();
        page=Page.PLAYER;updateNav();

        BorderPane pane=new BorderPane();pane.getStyleClass().add("player-screen");
        WebView web=new WebView();
        web.setContextMenuEnabled(false);
        WebEngine engine=web.getEngine();
        engine.setJavaScriptEnabled(true);
        engine.setCreatePopupHandler(features->null);

        Button back=new Button("‹ Back to details");back.getStyleClass().add("secondary-button");
        back.setOnAction(e->{try{engine.load("about:blank");}catch(Exception ignored){}page=Page.DETAILS;content.getChildren().setAll(detailsView(media));});
        Button browserBack=new Button("Back");browserBack.getStyleClass().add("secondary-button");browserBack.setOnAction(e->{var history=engine.getHistory();if(history.getCurrentIndex()>0)history.go(-1);});
        Button browserForward=new Button("Forward");browserForward.getStyleClass().add("secondary-button");browserForward.setOnAction(e->{var history=engine.getHistory();if(history.getCurrentIndex()+1<history.getEntries().size())history.go(1);});
        Button reload=new Button("Reload");reload.getStyleClass().add("secondary-button");reload.setOnAction(e->engine.reload());
        Button external=new Button("Open in browser ↗");external.getStyleClass().add("text-button");external.setOnAction(e->{URI location=safeUri(engine.getLocation());openUri(location==null?source.baseUri():location);});
        Label title=new Label(media.title()+" · "+source);title.getStyleClass().add("player-title");
        Region spacer=new Region();HBox.setHgrow(spacer,Priority.ALWAYS);
        HBox top=new HBox(9,back,browserBack,browserForward,reload,title,spacer,external);top.setAlignment(Pos.CENTER_LEFT);top.setPadding(new Insets(10,14,10,14));top.getStyleClass().add("player-top");
        pane.setTop(top);pane.setCenter(web);
        content.getChildren().setAll(pane);

        URI launch=providerLaunchUri(media,source);
        status.setText("Opening "+source.name()+" inside Aokuvue…");
        engine.getLoadWorker().stateProperty().addListener((obs,oldState,newState)->{
            if(newState==Worker.State.SUCCEEDED)status.setText(source.name()+" loaded. Select a chapter on the provider page.");
            else if(newState==Worker.State.FAILED){Throwable error=engine.getLoadWorker().getException();status.setText(source.name()+" failed to load: "+(error==null?"unknown error":root(error)));}
        });
        engine.load(launch.toString());
    }

    static URI providerLaunchUri(AniMedia media, ProviderSite source) {
        if(source==null||source.baseUri()==null)return EverythingMoeProviderDirectory.DIRECTORY_URI;
        if(media!=null&&"anikoto".equalsIgnoreCase(source.id())){
            String title=media.title()==null?"":media.title().trim();
            if(!title.isBlank())return URI.create("https://anikototv.to/filter?keyword="+URLEncoder.encode(title,StandardCharsets.UTF_8));
        }
        return source.baseUri();
    }

    private static URI safeUri(String raw) {
        try { URI uri=URI.create(raw==null?"":raw.trim());return uri.isAbsolute()?uri:null; }
        catch(Exception ignored){return null;}
    }

    private CompletableFuture<List<SubtitleCue>> loadSubtitleTrack(SubtitleTrack track) {
        if(track==null||track.uri()==null)return CompletableFuture.completedFuture(List.of());
        if("file".equalsIgnoreCase(track.uri().getScheme())){
            return CompletableFuture.supplyAsync(()->{
                try{return SubtitleParser.parse(Files.readString(Path.of(track.uri())));}
                catch(Exception e){throw new IllegalStateException("Unable to read subtitle file",e);}
            });
        }
        return app.http().getText(track.uri(),track.headers()).thenApply(SubtitleParser::parse);
    }

    private void choosePlayerServer(PlayerSession session) {
        status.setText("Loading video servers…");
        app.episodes().loadVideoServers(session.media(),session.playback()).whenComplete((servers,error)->Platform.runLater(()->{
            if(error!=null){status.setText("Server load failed: "+root(error));return;}
            if(servers.isEmpty()){status.setText("No video server is available for this episode.");return;}
            List<String> names=servers.stream().map(VideoServer::name).toList();
            int current=0;for(int i=0;i<servers.size();i++)if(servers.get(i).name().equalsIgnoreCase(session.playback().resolved().server().name())){current=i;break;}
            ChoiceDialog<String> dialog=new ChoiceDialog<>(names.get(current),names);dialog.setTitle("Choose video server");dialog.setHeaderText(session.media().title()+" · Episode "+session.playback().episode().number());dialog.setContentText("Server:");
            dialog.showAndWait().ifPresent(name->{int idx=names.indexOf(name);if(idx<0)return;status.setText("Resolving "+name+"…");app.episodes().resolveServer(session.media(),session.playback(),servers.get(idx)).whenComplete((resolution,resolveError)->Platform.runLater(()->{if(resolveError!=null)status.setText("Server failed: "+root(resolveError));else showPlayer(new PlayerSession(session.media(),session.episodeLoad(),resolution));}));});
        }));
    }

    private void playAdjacent(PlayerSession session,int delta) {
        List<SourceEpisode> eps=session.episodeLoad().episodes();
        int idx=-1;
        double currentNumber=session.playback().episode().numericNumber();
        for(int i=0;i<eps.size();i++){
            SourceEpisode candidate=eps.get(i);
            if(candidate.number()!=null&&session.playback().episode().number()!=null
                    &&candidate.number().equalsIgnoreCase(session.playback().episode().number())){idx=i;break;}
            double n=candidate.numericNumber();
            if(!Double.isNaN(currentNumber)&&!Double.isNaN(n)&&Math.abs(currentNumber-n)<0.0001){idx=i;break;}
        }
        if(idx<0){status.setText("Current episode is not present in the loaded episode index.");return;}
        int target=idx+delta;
        if(target<0||target>=eps.size()){status.setText(delta>0?"This is the last episode.":"This is the first episode.");return;}
        SourceEpisode ep=eps.get(target);status.setText("Resolving Episode "+ep.number()+"…");
        app.episodes().resolveEpisode(session.media(),session.episodeLoad(),ep).whenComplete((r,e)->Platform.runLater(()->{if(e!=null)status.setText(root(e));else showPlayer(new PlayerSession(session.media(),session.episodeLoad(),r));}));
    }

    private Node settingsView() {
        VBox body=new VBox(18);body.setPadding(new Insets(24,30,42,30));body.getStyleClass().add("page-body");
        Label title=new Label("Settings");title.getStyleClass().add("browse-title");
        Label subtitle=new Label("Customize your AOKUVUE experience.");subtitle.getStyleClass().add("browse-subtitle");
        Node settingsHeader=brandPageHeader(title,subtitle);
        Slider watchPct=new Slider(0.5,1.0,app.config().getDouble("player.watchPercentage",0.85));watchPct.setShowTickLabels(true);watchPct.setShowTickMarks(true);watchPct.setMajorTickUnit(0.1);
        CheckBox autoMarkWatched=new CheckBox("Automatically mark episodes as watched");autoMarkWatched.setSelected(app.config().getBoolean("player.autoMarkWatched",true));watchPct.disableProperty().bind(autoMarkWatched.selectedProperty().not());
        CheckBox autoPlay=new CheckBox("Autoplay resolved episodes");autoPlay.setSelected(app.config().getBoolean("player.autoPlay",true));
        Spinner<Integer> seekSeconds=new Spinner<>(5,60,Math.max(5,Math.min(60,app.config().getInt("player.seekSeconds",10))),5);seekSeconds.setEditable(true);
        CheckBox autoSkipIntro=new CheckBox("Automatically skip detected intros");autoSkipIntro.setSelected(app.config().getBoolean("player.autoSkipIntro",true));
        CheckBox autoSkipOutro=new CheckBox("Automatically skip detected outros");autoSkipOutro.setSelected(app.config().getBoolean("player.autoSkipOutro",true));
        CheckBox autoNextEpisode=new CheckBox("Automatically play the next episode");autoNextEpisode.setSelected(app.config().getBoolean("player.autoNextEpisode",true));
        ComboBox<Double> defaultSpeed=new ComboBox<>(FXCollections.observableArrayList(0.5,0.75,1.0,1.25,1.5,1.75,2.0));double savedSpeed=app.config().getDouble("player.defaultSpeed",1.0);defaultSpeed.setValue(defaultSpeed.getItems().stream().min(Comparator.comparingDouble(v->Math.abs(v-savedSpeed))).orElse(1.0));
        double savedSubtitleSize=PlayerSettings.sanitizeSubtitleSize(app.config().getDouble("player.subtitleSize",20.0));
        Slider subtitleSize=new Slider(14,48,savedSubtitleSize);subtitleSize.setShowTickLabels(true);subtitleSize.setShowTickMarks(true);subtitleSize.setMajorTickUnit(4);subtitleSize.setMinorTickCount(3);subtitleSize.setSnapToTicks(true);HBox.setHgrow(subtitleSize,Priority.ALWAYS);
        Label subtitleSizeValue=new Label();subtitleSizeValue.getStyleClass().add("subtitle-size-value");subtitleSizeValue.setMinWidth(48);
        Label subtitlePreview=new Label("This is how subtitles will look");subtitlePreview.getStyleClass().add("subtitle-overlay");
        StackPane subtitlePreviewPanel=new StackPane(subtitlePreview);subtitlePreviewPanel.getStyleClass().add("subtitle-preview-panel");subtitlePreviewPanel.setMinHeight(100);
        Runnable updateSubtitlePreview=()->{double size=Math.rint(subtitleSize.getValue());subtitleSizeValue.setText(String.format("%.0f px",size));subtitlePreview.setStyle("-fx-font-size: "+size+"px;");};
        subtitleSize.valueProperty().addListener((observable,oldValue,newValue)->updateSubtitlePreview.run());updateSubtitlePreview.run();
        VBox subtitleSizeControl=new VBox(9,new HBox(10,subtitleSize,subtitleSizeValue),subtitlePreviewPanel);
        ComboBox<String> startTab=new ComboBox<>(FXCollections.observableArrayList("HOME","ANIME","HENTAI","MANGA","MY_LIST"));startTab.setValue(app.config().get("ui.startTab","HOME").toUpperCase());
        Button login=new Button(viewer==null?"Login with AniList":"Reconnect AniList");login.getStyleClass().add("primary-button");login.setOnAction(e->loginAniList());
        Button logout=new Button("Log out");logout.setDisable(viewer==null);logout.getStyleClass().add("secondary-button");logout.setOnAction(e->{app.auth().clearToken();viewer=null;animeList=List.of();mangaList=List.of();applyViewer(null);show(Page.SETTINGS);});
        Label noSources=new Label("EverythingMoe ranks the directory. Aokuvue automatically uses the highest-ranked provider with a working episode adapter, currently Anikoto, and opens selected episodes in the in-app player.");noSources.setWrapText(true);
        VBox sourceCard=settingsCard("EverythingMoe sources",noSources);
        VBox providerDirectoryCard=providerDirectoryCard();
        VBox hentaiDirectoryCard=hentaiDirectoryCard();
        VBox playerCard=settingsCard("Player",autoMarkWatched,field("Mark episode watched at",watchPct),autoPlay,field("Keyboard seek seconds",seekSeconds),autoSkipIntro,autoSkipOutro,autoNextEpisode,field("Default speed",defaultSpeed),field("Subtitle size",subtitleSizeControl));
        VBox uiCard=settingsCard("Interface",field("Startup tab",startTab));
        VBox accountCard=settingsCard("AniList",new HBox(8,login,logout),new Label("OAuth client ID "+AniListAuthService.CLIENT_ID+" · browser/Auth Pin login"));
        Button save=new Button("Save settings");save.getStyleClass().add("primary-button");save.setOnAction(e->{
            app.config().set("player.watchPercentage",Double.toString(watchPct.getValue()));
            app.config().set("player.autoMarkWatched",Boolean.toString(autoMarkWatched.isSelected()));
            app.config().set("player.defaultSpeed",Double.toString(defaultSpeed.getValue()==null?1.0:defaultSpeed.getValue()));
            app.config().set("player.subtitleSize",Double.toString(Math.rint(subtitleSize.getValue())));
            app.config().set("player.autoPlay",Boolean.toString(autoPlay.isSelected()));
            app.config().set("player.seekSeconds",Integer.toString(seekSeconds.getValue()));
            app.config().set("player.autoSkipIntro",Boolean.toString(autoSkipIntro.isSelected()));
            app.config().set("player.autoSkipOutro",Boolean.toString(autoSkipOutro.isSelected()));
            app.config().set("player.autoNextEpisode",Boolean.toString(autoNextEpisode.isSelected()));
            app.config().set("ui.startTab",startTab.getValue());
            app.player().reloadSettings();
            status.setText("Settings saved. Subtitle size will be used by the player.");
        });
        VBox themeCard=themePreviewCard();HBox.setHgrow(themeCard,Priority.ALWAYS);uiCard.setPrefWidth(330);HBox appearanceRow=new HBox(14,themeCard,uiCard);
        VBox appearance=new VBox(14,appearanceRow);appearance.setPadding(new Insets(18,0,0,0));
        VBox playback=new VBox(14,playerCard);playback.setPadding(new Insets(18,0,0,0));
        VBox sources=new VBox(14,sourceCard,providerDirectoryCard,hentaiDirectoryCard);sources.setPadding(new Insets(18,0,0,0));
        VBox account=new VBox(14,accountCard);account.setPadding(new Insets(18,0,0,0));
        VBox feedback=new VBox(14,feedbackCard());feedback.setPadding(new Insets(18,0,0,0));
        TabPane tabs=new TabPane();tabs.getStyleClass().add("settings-tabs");tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().addAll(new Tab("Appearance",appearance),new Tab("Playback",playback),new Tab("Sources",sources),new Tab("Account",account),new Tab("Suggestions / Bugs",feedback));
        body.getChildren().addAll(settingsHeader,tabs,save);return scroll(body);
    }

    private VBox feedbackCard(){
        ComboBox<String> kind=new ComboBox<>(FXCollections.observableArrayList("Suggestion","Bug Report"));
        kind.setValue("Suggestion");kind.setMaxWidth(260);
        TextField title=new TextField();title.setPromptText("Give your suggestion a short title");title.setMaxWidth(Double.MAX_VALUE);
        Label titleCounter=new Label("0 / "+FeedbackService.MAX_TITLE_LENGTH);titleCounter.getStyleClass().add("source-status");
        TextArea message=new TextArea();message.setWrapText(true);message.setPrefRowCount(9);message.setPromptText("Describe your suggestion…");
        Label counter=new Label("0 / 1700");counter.getStyleClass().add("source-status");
        Label privacy=new Label("Suggestions send only the title and message. Bug reports also attach a redacted excerpt of recent AOKUVUE runtime logs and basic OS/Java version information. Account tokens, URLs, cookies, and webhook values are excluded.");
        privacy.setWrapText(true);privacy.getStyleClass().add("source-status");
        Label result=new Label();result.setWrapText(true);result.getStyleClass().add("source-status");
        ProgressIndicator sending=new ProgressIndicator();sending.setMaxSize(22,22);sending.setVisible(false);sending.setManaged(false);
        Button submit=new Button("Send suggestion");submit.getStyleClass().add("primary-button");
        title.textProperty().addListener((observable,oldValue,newValue)->{
            if(newValue.length()>FeedbackService.MAX_TITLE_LENGTH){title.setText(oldValue);return;}
            titleCounter.setText(newValue.length()+" / "+FeedbackService.MAX_TITLE_LENGTH);
        });
        message.textProperty().addListener((observable,oldValue,newValue)->{
            if(newValue.length()>1700){message.setText(oldValue);return;}
            counter.setText(newValue.length()+" / 1700");
        });
        kind.valueProperty().addListener((observable,oldValue,newValue)->{
            boolean bug="Bug Report".equals(newValue);
            submit.setText(bug?"Send bug report":"Send suggestion");
            title.setPromptText(bug?"Give the bug report a short title":"Give your suggestion a short title");
            message.setPromptText(bug?"Describe what happened, what you expected, and how to reproduce it…":"Describe your suggestion…");
            result.setText("");
        });
        submit.setOnAction(event->{
            String reportTitle=title.getText()==null?"":title.getText().strip();
            String text=message.getText()==null?"":message.getText().strip();
            if(reportTitle.isBlank()){result.setText("Enter a title before sending.");title.requestFocus();return;}
            if(text.isBlank()){result.setText("Enter a message before sending.");message.requestFocus();return;}
            FeedbackService.Kind selected="Bug Report".equals(kind.getValue())?FeedbackService.Kind.BUG_REPORT:FeedbackService.Kind.SUGGESTION;
            submit.setDisable(true);kind.setDisable(true);title.setDisable(true);message.setDisable(true);sending.setManaged(true);sending.setVisible(true);result.setText("Sending securely to the AOKUVUE Discord webhook…");
            app.feedback().submit(selected,reportTitle,text).whenComplete((ignored,error)->Platform.runLater(()->{
                submit.setDisable(false);kind.setDisable(false);title.setDisable(false);message.setDisable(false);sending.setManaged(false);sending.setVisible(false);
                if(error==null){title.clear();message.clear();result.setText(selected==FeedbackService.Kind.BUG_REPORT?"Bug report and redacted diagnostics sent.":"Suggestion sent. Thank you.");status.setText("Feedback sent successfully.");}
                else {result.setText("Could not send feedback: "+root(error));status.setText("Feedback submission failed.");}
            }));
        });
        HBox action=new HBox(10,submit,sending,result);action.setAlignment(Pos.CENTER_LEFT);HBox.setHgrow(result,Priority.ALWAYS);
        return settingsCard("Suggestions & Bugs",field("Submission type",kind),field("Title",new VBox(6,title,titleCounter)),field("Message",new VBox(6,message,counter)),privacy,action);
    }

    private Node brandPageHeader(Label title,Label subtitle){
        Region art=new Region();var resource=MainWindow.class.getResource("/images/aokuvue-moonlight.png");if(resource!=null)art.setBackground(new Background(new BackgroundImage(new Image(resource.toExternalForm(),true),BackgroundRepeat.NO_REPEAT,BackgroundRepeat.NO_REPEAT,BackgroundPosition.CENTER,new BackgroundSize(100,100,true,true,false,true))));art.setMaxSize(Double.MAX_VALUE,Double.MAX_VALUE);art.getStyleClass().add("brand-header-art");
        Region veil=new Region();veil.setMaxSize(Double.MAX_VALUE,Double.MAX_VALUE);veil.getStyleClass().add("brand-header-veil");VBox copy=new VBox(3,title,subtitle);StackPane header=new StackPane(art,veil,copy);header.setMinHeight(160);header.getStyleClass().add("brand-page-header");StackPane.setAlignment(copy,Pos.CENTER_LEFT);StackPane.setMargin(copy,new Insets(20,26,20,10));return header;
    }

    private VBox themePreviewCard(){
        Label kicker=new Label("THEME & APPEARANCE");kicker.getStyleClass().add("section-kicker");Label title=new Label("Obsidian");title.getStyleClass().add("section-title");Label description=new Label("The fixed AOKUVUE identity · cinematic darkness illuminated by violet moonlight.");description.setWrapText(true);description.getStyleClass().add("source-status");ImageView preview=new ImageView();var resource=MainWindow.class.getResource("/images/aokuvue-moonlight.png");if(resource!=null)preview.setImage(new Image(resource.toExternalForm(),640,170,true,true));preview.setFitHeight(170);preview.setPreserveRatio(true);preview.getStyleClass().add("theme-preview");VBox card=new VBox(9,kicker,title,description,preview);card.getStyleClass().add("settings-card");card.setPadding(new Insets(18));return card;
    }


    private VBox providerDirectoryCard() {
        Label summary = new Label("Refreshing provider directory…");
        summary.getStyleClass().add("source-status");
        summary.setWrapText(true);

        FlowPane providers = new FlowPane(7,7);
        providers.setAlignment(Pos.CENTER_LEFT);

        Button refresh = new Button("Refresh provider directory");
        refresh.getStyleClass().add("secondary-button");

        VBox card = settingsCard(
                "EverythingMoe source directory",
                new Label("Reference-only rankings from EverythingMoe's Anime Streaming list. These entries are not registered as episode or playback sources."),
                summary,
                providers,
                refresh
        );
        ((Label) card.getChildren().get(2)).setWrapText(true);

        Runnable load = () -> {
            summary.setText("Refreshing anime provider directory…");
            refresh.setDisable(true);
            app.providerDirectory().refresh().whenComplete((sites,error)->Platform.runLater(()->{
                refresh.setDisable(false);
                List<ProviderSite> resolved = sites == null ? app.providerDirectory().fallbackSnapshot() : sites;
                summary.setText(resolved.size()+" directory entries · 0 registered sources");
                providers.getChildren().clear();
                for (int i=0;i<resolved.size();i++) {
                    ProviderSite site = resolved.get(i);
                    Label chip = new Label("#"+site.rank()+"  "+site.name()+(site.multiSource()?" · MULT":"")+" · directory");
                    chip.getStyleClass().add("provider-chip");
                    Circle sourceDot = new Circle(4);
                    sourceDot.getStyleClass().addAll("source-connection-dot", "connection-checking");
                    Tooltip sourceTooltip = new Tooltip(site.name()+" · Checking connection…");
                    StackPane sourceIndicator = new StackPane(sourceDot);
                    sourceIndicator.getStyleClass().add("source-connection-indicator");
                    Tooltip.install(sourceIndicator, sourceTooltip);
                    sourceIndicator.setOnMouseEntered(e -> sourceTooltip.show(sourceIndicator,
                            sourceIndicator.localToScreen(sourceIndicator.getBoundsInLocal()).getMinX() - 45,
                            sourceIndicator.localToScreen(sourceIndicator.getBoundsInLocal()).getMaxY() + 7));
                    sourceIndicator.setOnMouseExited(e -> sourceTooltip.hide());
                    HBox sourceChip = new HBox(7, sourceDot, chip);
                    sourceChip.setAlignment(Pos.CENTER_LEFT);
                    sourceChip.getStyleClass().add("provider-chip");
                    Tooltip.install(sourceChip, sourceTooltip);
                    providers.getChildren().add(sourceChip);
                    checkSourceConnection(site, sourceDot, sourceTooltip);
                }
                if(error!=null) status.setText("Provider directory refresh used the built-in fallback list.");
            }));
        };
        refresh.setOnAction(e -> load.run());
        Platform.runLater(load);
        return card;
    }

    private void checkSourceConnection(ProviderSite site, Circle dot, Tooltip tooltip) {
        CompletableFuture.supplyAsync(() -> {
            long started = System.nanoTime();
            try {
                HttpURLConnection connection = (HttpURLConnection) site.baseUri().toURL().openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(4000);
                connection.setReadTimeout(4000);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("User-Agent", "Aokuvue/1.0");
                int code = connection.getResponseCode();
                connection.disconnect();
                long latencyMs = Math.max(1L, (System.nanoTime() - started) / 1_000_000L);
                return new long[]{code >= 200 && code < 500 ? 1 : 0, latencyMs, code};
            } catch (Exception ignored) {
                return new long[]{0, -1, -1};
            }
        }).thenAccept(result -> Platform.runLater(() -> {
            dot.getStyleClass().removeAll("connection-checking", "connection-perfect", "connection-medium", "connection-bad");
            if (result[0] == 0) {
                dot.getStyleClass().add("connection-bad");
                tooltip.setText(site.name()+" · Bad · unreachable");
            } else if (result[1] <= 300) {
                dot.getStyleClass().add("connection-perfect");
                tooltip.setText(site.name()+" · Perfect · "+result[1]+" ms · HTTP "+result[2]);
            } else if (result[1] <= 900) {
                dot.getStyleClass().add("connection-medium");
                tooltip.setText(site.name()+" · Medium · "+result[1]+" ms · HTTP "+result[2]);
            } else {
                dot.getStyleClass().add("connection-bad");
                tooltip.setText(site.name()+" · Bad · "+result[1]+" ms · HTTP "+result[2]);
            }
        }));
    }

    private void runHentaiSearch() {
        String q=search.getText().trim();if(q.isBlank())return;
        page=Page.SEARCH;updateNav();status.setText("Searching Hentai for “"+q+"”…");
        VBox loading=new VBox(12,new ProgressIndicator(),new Label("Searching…"));loading.setAlignment(Pos.CENTER);content.getChildren().setAll(loading);
        app.anilist().searchHentai(q,36).whenComplete((items,error)->Platform.runLater(()->{
            if(error!=null){status.setText("Search failed: "+root(error));content.getChildren().setAll(errorCard(root(error)));return;}
            status.setText("Found "+items.size()+" Hentai results.");content.getChildren().setAll(searchResultsView(q,items));
        }));
    }

    private VBox hentaiDirectoryCard() {
        Label summary = new Label("Refreshing adult provider directory…");summary.getStyleClass().add("source-status");summary.setWrapText(true);
        FlowPane providers = new FlowPane(7,7);providers.setAlignment(Pos.CENTER_LEFT);
        Button directory = new Button("Open Hentai Streaming directory");directory.getStyleClass().add("secondary-button");directory.setOnAction(e -> openUri(EverythingMoeHentaiDirectory.DIRECTORY_URI));
        Button refresh = new Button("Refresh adult sources");refresh.getStyleClass().add("secondary-button");
        Label explanation = new Label("Ranked adult-site directory from EverythingMoe. Entries open as websites and are not registered as native episode resolvers.");explanation.setWrapText(true);
        VBox card = settingsCard("EverythingMoe Hentai sources",explanation,summary,providers,new HBox(8,directory,refresh));
        Runnable load = () -> {
            summary.setText("Refreshing adult provider directory…");refresh.setDisable(true);
            app.hentaiDirectory().refresh().whenComplete((sites,error)->Platform.runLater(()->{
                refresh.setDisable(false);List<ProviderSite> resolved = sites == null ? app.hentaiDirectory().fallbackSnapshot() : sites;
                summary.setText(resolved.size()+" ranked adult sources · browser directory");providers.getChildren().clear();
                for (ProviderSite site : resolved) {
                    Button chip = new Button("#"+site.rank()+"  "+site.name()+(site.multiSource()?" · MULT":""));chip.getStyleClass().add("provider-chip");chip.setTooltip(new Tooltip("Open "+site.baseUri()));chip.setOnAction(e -> openUri(site.baseUri()));providers.getChildren().add(chip);
                }
                if(error!=null) status.setText("Adult provider refresh used the built-in fallback list.");
            }));
        };
        refresh.setOnAction(e -> load.run());Platform.runLater(load);return card;
    }

    private VBox settingsCard(String title,Node...nodes){Label eyebrow=new Label("AOKUVUE / "+title.toUpperCase());eyebrow.getStyleClass().add("section-kicker");Label h=new Label(title);h.getStyleClass().add("section-title");VBox v=new VBox(11,eyebrow,h);v.getChildren().addAll(nodes);v.getStyleClass().add("settings-card");v.setPadding(new Insets(18));return v;}
    private VBox field(String name,Node control){Label l=new Label(name);l.getStyleClass().add("field-label");if(control instanceof Region r)r.setMaxWidth(Double.MAX_VALUE);return new VBox(5,l,control);}

    private void loginAniList() {
        try { app.auth().openBrowser(); }
        catch(Exception e){status.setText(root(e));return;}
        Dialog<String> d=new Dialog<>();d.setTitle("Aokuvue · AniList");d.setHeaderText("Finish AniList login in your browser");ButtonType connect=new ButtonType("Connect",ButtonBar.ButtonData.OK_DONE);d.getDialogPane().getButtonTypes().addAll(connect,ButtonType.CANCEL);
        Label instructions=new Label("Approve Aokuvue in the browser, then copy the access token from AniList's Auth Pin page and paste it below.");instructions.setWrapText(true);PasswordField token=new PasswordField();token.setPromptText("AniList access token");VBox box=new VBox(10,instructions,token);box.setPrefWidth(470);d.getDialogPane().setContent(box);d.setResultConverter(t->t==connect?token.getText().trim():null);
        d.showAndWait().ifPresent(value->{if(value.isBlank())return;status.setText("Validating AniList account…");app.auth().saveToken(value);app.anilist().viewer().whenComplete((v,error)->Platform.runLater(()->{if(error!=null){app.auth().clearToken();status.setText("AniList login failed: "+root(error));return;}viewer=v;applyViewer(v);refreshLists();show(Page.HOME);}));});
    }

    private void restoreViewer() {
        if(!app.auth().loggedIn()){applyViewer(null);return;}
        app.anilist().viewer().whenComplete((v,error)->Platform.runLater(()->{if(error!=null){applyViewer(null);status.setText("Saved AniList session needs login again.");return;}viewer=v;applyViewer(v);refreshLists();}));
    }

    private void applyViewer(Viewer v) {
        if(v==null){accountName.setText("Login");accountAvatar.setImage(null);return;}accountName.setText(v.name());if(v.avatarUrl()!=null&&!v.avatarUrl().isBlank())accountAvatar.setImage(new Image(v.avatarUrl(),30,30,true,true,true));else accountAvatar.setImage(null);
    }

    private void refreshLists() {
        if(viewer==null)return;
        CompletableFuture<List<AniMedia>> a=app.anilist().listCollection(viewer.id(),MediaType.ANIME);CompletableFuture<List<AniMedia>> m=app.anilist().listCollection(viewer.id(),MediaType.MANGA);
        CompletableFuture.allOf(a,m).whenComplete((x,error)->Platform.runLater(()->{if(error!=null){status.setText("AniList list sync failed: "+root(error));return;}animeList=a.join();mangaList=m.join();status.setText("AniList library synced.");if(page==Page.HOME||page==Page.MY_LIST)show(page);}));
    }

    private void loadHome() {
        homeLoadError=null;if(page==Page.HOME)show(Page.HOME);status.setText("Loading AniList…");
        var ta=app.anilist().browse(MediaType.ANIME,"TRENDING_DESC",18);
        var pa=app.anilist().browse(MediaType.ANIME,"POPULARITY_DESC",24);
        var ha=app.anilist().browseAdultAnime("POPULARITY_DESC",36);
        var tm=app.anilist().browse(MediaType.MANGA,"TRENDING_DESC",18);
        int[] pending={4};
        Runnable finished=()->{refreshHomeAfterLoad();if(--pending[0]==0)initialContentReady.complete(null);};
        ta.whenComplete((items,error)->Platform.runLater(()->{if(error==null)trendingAnime=items;else homeLoadError=root(error);finished.run();}));
        pa.whenComplete((items,error)->Platform.runLater(()->{if(error==null)popularAnime=items;else if(homeLoadError==null)homeLoadError=root(error);finished.run();}));
        ha.whenComplete((items,error)->Platform.runLater(()->{if(error==null)adultAnime=items;else if(homeLoadError==null)homeLoadError=root(error);finished.run();}));
        tm.whenComplete((items,error)->Platform.runLater(()->{if(error==null)trendingManga=items;else if(homeLoadError==null)homeLoadError=root(error);finished.run();}));
    }

    private void refreshHomeAfterLoad(){boolean any=!trendingAnime.isEmpty()||!popularAnime.isEmpty()||!adultAnime.isEmpty()||!trendingManga.isEmpty();status.setText(any?"AniList content loaded.":"AniList loading failed: "+homeLoadError);System.out.println("[Aokuvue][Home] trending="+trendingAnime.size()+" popular="+popularAnime.size()+" hentai="+adultAnime.size()+" manga="+trendingManga.size()+(homeLoadError==null?"":" lastError="+homeLoadError));if(page==Page.HOME)show(Page.HOME);else if(page==Page.ANIME||page==Page.HENTAI||page==Page.MANGA)show(page);}

    private static Button nav(String text){Button b=new Button(text);b.getStyleClass().add("nav-tab");return b;}
    private static Button icon(String text,String tooltip){Button b=new Button(text);b.getStyleClass().add("icon-button");b.setTooltip(new Tooltip(tooltip));return b;}
    private ScrollPane scroll(Node n){ScrollPane s=new ScrollPane(n);s.setFitToWidth(true);s.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);s.getStyleClass().add("page-scroll");return s;}
    private static ImageView image(String url,double w,double h){ImageView v=new ImageView();v.setFitWidth(w);v.setFitHeight(h);v.setPreserveRatio(true);v.setSmooth(true);if(url!=null&&!url.isBlank())v.setImage(new Image(url,w,h,true,true,true));return v;}
    private static ImageView coverImage(String url,double w,double h){
        ImageView v=new ImageView();
        v.setFitWidth(w);v.setFitHeight(h);v.setPreserveRatio(false);v.setSmooth(true);
        Rectangle clip=new Rectangle(w,h);clip.setArcWidth(6);clip.setArcHeight(6);v.setClip(clip);
        if(url!=null&&!url.isBlank()){
            Image img=new Image(url,true);v.setImage(img);
            v.setViewport(null);
            img.widthProperty().addListener((o,a,b)->applyCoverViewport(v,img,w,h));
            img.heightProperty().addListener((o,a,b)->applyCoverViewport(v,img,w,h));
            if(img.getWidth()>0&&img.getHeight()>0)applyCoverViewport(v,img,w,h);
        }
        return v;
    }
    private static void applyCoverViewport(ImageView v,Image img,double w,double h){
        double iw=img.getWidth(),ih=img.getHeight();if(iw<=0||ih<=0)return;
        double target=w/h,image=iw/ih;
        if(image>target){double vw=ih*target;v.setViewport(new javafx.geometry.Rectangle2D((iw-vw)/2,0,vw,ih));}
        else {double vh=iw/target;v.setViewport(new javafx.geometry.Rectangle2D(0,(ih-vh)/2,iw,vh));}
    }
    private Node loadingCard(String text){VBox v=new VBox(12,new ProgressIndicator(),new Label(text));v.setAlignment(Pos.CENTER);v.setMinHeight(260);v.getStyleClass().add("empty-state");return v;}
    private Node errorCard(String text){Label l=new Label(text);l.setWrapText(true);VBox v=new VBox(l);v.setPadding(new Insets(30));v.getStyleClass().add("empty-state");return v;}
    private static String trim(String s,int max){if(s==null)return"";return s.length()<=max?s:s.substring(0,Math.max(0,max-1)).trim()+"…";}
    private static String safe(Object o){return o==null?"":o.toString();}
    private static String videoLabel(PlaybackSource source){String quality=source.quality()==null?"Auto":source.quality()+"p";String container=source.container()==null||source.container().isBlank()?"":(" · "+source.container().toUpperCase());String label=source.label()==null||source.label().isBlank()?"":(" · "+source.label());return quality+container+label;}
    private static String meta(AniMedia m){List<String>x=new ArrayList<>();if(m.format()!=null&&!m.format().isBlank())x.add(m.format().replace('_',' '));if(m.averageScore()!=null)x.add("★ "+m.averageScore()+"%");if(m.type()==MediaType.MANGA&&m.totalChapters()!=null)x.add(m.totalChapters()+" chapters");else if(m.totalEpisodes()!=null)x.add(m.totalEpisodes()+" episodes");if(m.releaseStatus()!=null&&!m.releaseStatus().isBlank())x.add(m.releaseStatus().replace('_',' '));return String.join(" · ",x);}
    private static void addInfo(GridPane g,int row,String a,String b,String c,String d){Label la=new Label(a),lb=new Label(b==null?"":b),lc=new Label(c),ld=new Label(d==null?"":d);la.getStyleClass().add("info-label");lc.getStyleClass().add("info-label");lb.setWrapText(true);ld.setWrapText(true);g.addRow(row,la,lb,lc,ld);}
    private void openUri(URI uri){if(uri==null)return;try{Desktop.getDesktop().browse(uri);}catch(Exception e){status.setText("Unable to open browser: "+root(e));}}
    private static String clock(double ms){if(Double.isNaN(ms)||Double.isInfinite(ms)||ms<0)return"00:00";long sec=(long)(ms/1000);return String.format("%02d:%02d",sec/60,sec%60);}
    private static String root(Throwable e){Throwable c=e;while(c.getCause()!=null)c=c.getCause();return c.getMessage()==null?c.getClass().getSimpleName():c.getMessage();}
}
