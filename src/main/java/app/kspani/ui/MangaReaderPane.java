package app.kspani.ui;

import app.kspani.app.JsonHttpClient;
import app.kspani.domain.AniMedia;
import app.kspani.manga.MangaDexReaderService;
import app.kspani.manga.MangaReadingProgress;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;

import java.util.List;
import java.util.function.Consumer;

/** Native single-page manga reader backed by the MangaDex source ranked by EverythingMoe. */
final class MangaReaderPane extends BorderPane {
    private final AniMedia media;
    private final MangaDexReaderService service;
    private final MangaReadingProgress progress = new MangaReadingProgress();
    private final Consumer<String> status;
    private final ComboBox<MangaDexReaderService.MangaMatch> matches = new ComboBox<>();
    private final ListView<MangaDexReaderService.Chapter> chapters = new ListView<>();
    private final ImageView pageImage = new ImageView();
    private final StackPane pageStage = new StackPane();
    private final ScrollPane pageScroll = new ScrollPane(pageStage);
    private final Label readerState = new Label("Finding the best MangaDex match…");
    private final Label pageNumber = new Label("Page — / —");
    private final Label credit = new Label("Powered by MangaDex · Select a chapter to view scanlation credits");
    private final ProgressIndicator loading = new ProgressIndicator();
    private final Button previousPage = button("‹ Page");
    private final Button nextPage = button("Page ›");
    private final Button previousChapter = button("‹ Chapter");
    private final Button nextChapter = button("Chapter ›");
    private final Slider zoom = new Slider(.55, 1.45, 1.0);
    private MangaReadingProgress.Bookmark bookmark;
    private List<MangaDexReaderService.Chapter> chapterItems = List.of();
    private MangaDexReaderService.Chapter currentChapter;
    private List<java.net.URI> pageUris = List.of();
    private int currentPage;
    private long loadGeneration;

    MangaReaderPane(JsonHttpClient http, AniMedia media, Runnable back, Consumer<String> status) {
        this.media = media;
        this.service = new MangaDexReaderService(http);
        this.status = status;
        this.bookmark = progress.load(media.id());
        getStyleClass().add("manga-reader");

        Button backButton = button("‹ Back to details");
        backButton.setOnAction(event -> back.run());
        Label title = new Label(media.title()); title.getStyleClass().add("reader-title");
        Label source = new Label("MangaDex · EverythingMoe ranked source"); source.getStyleClass().add("reader-source");
        VBox heading = new VBox(2, title, source);
        Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
        matches.setPromptText("Choose title match"); matches.setPrefWidth(330);
        matches.setOnAction(event -> { if (matches.getValue() != null) loadChapters(matches.getValue()); });
        HBox top = new HBox(12, backButton, heading, spacer, new Label("Match"), matches);
        top.setAlignment(Pos.CENTER_LEFT); top.setPadding(new Insets(10, 16, 10, 16)); top.getStyleClass().add("reader-top");
        setTop(top);

        chapters.setPrefWidth(310); chapters.setMinWidth(250); chapters.getStyleClass().add("reader-chapters");
        chapters.setCellFactory(view -> new ChapterCell());
        chapters.getSelectionModel().selectedItemProperty().addListener((observable, before, selected) -> {
            if (selected != null && selected != currentChapter) {
                MangaDexReaderService.MangaMatch match = matches.getValue();
                int resumePage = match != null && match.id().equals(bookmark.mangaId())
                        && selected.id().equals(bookmark.chapterId()) ? bookmark.page() : 0;
                loadChapter(selected, resumePage);
            }
        });
        VBox chapterPanel = new VBox(10, sectionLabel("CHAPTERS"), chapters);
        chapterPanel.setPadding(new Insets(15)); chapterPanel.getStyleClass().add("reader-sidebar");
        VBox.setVgrow(chapters, Priority.ALWAYS);
        setLeft(chapterPanel);

        pageImage.setPreserveRatio(true); pageImage.setSmooth(true); pageImage.setCache(true);
        pageStage.getChildren().addAll(pageImage, loading, readerState);
        StackPane.setAlignment(readerState, Pos.CENTER);
        loading.setMaxSize(42, 42); loading.setVisible(false);
        StackPane.setAlignment(loading, Pos.CENTER);
        pageStage.setMinSize(700, 700); pageStage.getStyleClass().add("reader-page-stage");
        pageScroll.setFitToWidth(true); pageScroll.setFitToHeight(true); pageScroll.setPannable(true);
        pageScroll.getStyleClass().add("reader-scroll");
        pageImage.fitWidthProperty().bind(pageScroll.viewportBoundsProperty().map(bounds ->
                Math.max(420, (bounds.getWidth() - 48) * zoom.getValue())));
        zoom.valueProperty().addListener((observable, before, after) -> {
            pageImage.fitWidthProperty().unbind();
            pageImage.fitWidthProperty().bind(pageScroll.viewportBoundsProperty().map(bounds ->
                    Math.max(420, (bounds.getWidth() - 48) * zoom.getValue())));
        });
        setCenter(pageScroll);

        previousPage.setOnAction(event -> movePage(-1)); nextPage.setOnAction(event -> movePage(1));
        previousChapter.setOnAction(event -> moveChapter(-1)); nextChapter.setOnAction(event -> moveChapter(1));
        zoom.setPrefWidth(130);
        credit.getStyleClass().add("reader-credit"); credit.setWrapText(true);
        Region bottomSpacer = new Region(); HBox.setHgrow(bottomSpacer, Priority.ALWAYS);
        HBox controls = new HBox(9, previousChapter, previousPage, pageNumber, nextPage, nextChapter,
                bottomSpacer, new Label("Zoom"), zoom);
        controls.setAlignment(Pos.CENTER); controls.getStyleClass().add("reader-controls");
        VBox bottom = new VBox(6, controls, credit); bottom.setPadding(new Insets(9, 16, 10, 16));
        bottom.getStyleClass().add("reader-bottom"); setBottom(bottom);

        setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.LEFT) { movePage(-1); event.consume(); }
            else if (event.getCode() == KeyCode.RIGHT || event.getCode() == KeyCode.SPACE) { movePage(1); event.consume(); }
            else if (event.getCode() == KeyCode.PAGE_UP) { moveChapter(-1); event.consume(); }
            else if (event.getCode() == KeyCode.PAGE_DOWN) { moveChapter(1); event.consume(); }
        });
        Platform.runLater(this::requestFocus);
        refreshControls();
        searchMatches();
    }

    private void searchMatches() {
        long generation = ++loadGeneration;
        loading(true, "Searching MangaDex for “" + media.title() + "”…");
        service.search(media.title()).whenComplete((items, error) -> Platform.runLater(() -> {
            if (generation != loadGeneration) return;
            if (error != null) { fail("Manga search failed", error); return; }
            if (items == null || items.isEmpty()) { loading(false, "No MangaDex match was found. Choose another EverythingMoe source from Details."); return; }
            matches.setItems(FXCollections.observableArrayList(items));
            MangaDexReaderService.MangaMatch selected = items.stream()
                    .filter(item -> item.id().equals(bookmark.mangaId())).findFirst().orElse(items.get(0));
            matches.setValue(selected);
        }));
    }

    private void loadChapters(MangaDexReaderService.MangaMatch match) {
        long generation = ++loadGeneration;
        loading(true, "Loading English chapters…");
        service.chapters(match.id()).whenComplete((items, error) -> Platform.runLater(() -> {
            if (generation != loadGeneration) return;
            if (error != null) { fail("Chapter loading failed", error); return; }
            chapterItems = items == null ? List.of() : items;
            chapters.setItems(FXCollections.observableArrayList(chapterItems));
            if (chapterItems.isEmpty()) { loading(false, "No readable English chapters are currently available."); refreshControls(); return; }
            MangaDexReaderService.Chapter selected = match.id().equals(bookmark.mangaId())
                    ? chapterItems.stream().filter(item -> item.id().equals(bookmark.chapterId())).findFirst().orElse(chapterItems.get(0))
                    : chapterItems.get(0);
            chapters.getSelectionModel().select(selected);
            chapters.scrollTo(selected);
        }));
    }

    private void loadChapter(MangaDexReaderService.Chapter chapter, int requestedPage) {
        long generation = ++loadGeneration;
        currentChapter = chapter; pageUris = List.of(); currentPage = 0;
        credit.setText("Powered by MangaDex · Translation: " + chapter.group());
        loading(true, "Loading " + chapter + "…"); refreshControls();
        service.pages(chapter.id()).whenComplete((result, error) -> Platform.runLater(() -> {
            if (generation != loadGeneration || chapter != currentChapter) return;
            if (error != null) { fail("Page loading failed", error); return; }
            pageUris = result.pages(); currentPage = Math.min(Math.max(0, requestedPage), pageUris.size() - 1);
            showCurrentPage();
        }));
    }

    private void showCurrentPage() {
        if (pageUris.isEmpty() || currentChapter == null) return;
        loading(true, "Loading page " + (currentPage + 1) + "…");
        Image image = new Image(pageUris.get(currentPage).toString(), true);
        pageImage.setImage(image);
        image.progressProperty().addListener((observable, before, after) -> {
            if (after.doubleValue() >= 1 && !image.isError()) Platform.runLater(() -> loading(false, ""));
        });
        image.errorProperty().addListener((observable, before, failed) -> {
            if (failed) Platform.runLater(() -> loading(false, "Page image could not be loaded."));
        });
        if (image.getProgress() >= 1 && !image.isError()) loading(false, "");
        pageScroll.setHvalue(0.5); pageScroll.setVvalue(0);
        MangaDexReaderService.MangaMatch match = matches.getValue();
        if (match != null) progress.save(media.id(), match.id(), currentChapter.id(), currentPage);
        pageNumber.setText("Page " + (currentPage + 1) + " / " + pageUris.size());
        status.accept(media.title() + " · " + currentChapter + " · Page " + (currentPage + 1));
        refreshControls();
    }

    private void movePage(int delta) {
        if (pageUris.isEmpty()) return;
        int next = currentPage + delta;
        if (next < 0) { moveChapter(-1, true); return; }
        if (next >= pageUris.size()) { moveChapter(1, false); return; }
        currentPage = next; showCurrentPage();
    }

    private void moveChapter(int delta) { moveChapter(delta, false); }
    private void moveChapter(int delta, boolean lastPage) {
        if (currentChapter == null) return;
        int index = chapterItems.indexOf(currentChapter); int next = index + delta;
        if (next < 0 || next >= chapterItems.size()) return;
        MangaDexReaderService.Chapter chapter = chapterItems.get(next);
        chapters.getSelectionModel().select(chapter); chapters.scrollTo(chapter);
        loadChapter(chapter, lastPage ? Math.max(0, chapter.pages() - 1) : 0);
    }

    private void loading(boolean active, String text) {
        loading.setVisible(active); readerState.setText(text); readerState.setVisible(!text.isBlank());
        if (!active && text.isBlank()) readerState.setVisible(false);
    }

    private void fail(String prefix, Throwable error) {
        String message = rootMessage(error); loading(false, prefix + ": " + message); status.accept(prefix + ": " + message);
    }

    private void refreshControls() {
        int chapter = currentChapter == null ? -1 : chapterItems.indexOf(currentChapter);
        previousChapter.setDisable(chapter <= 0); nextChapter.setDisable(chapter < 0 || chapter + 1 >= chapterItems.size());
        previousPage.setDisable(pageUris.isEmpty() || (currentPage <= 0 && chapter <= 0));
        nextPage.setDisable(pageUris.isEmpty() || (currentPage + 1 >= pageUris.size() && chapter + 1 >= chapterItems.size()));
    }

    private static Button button(String text) { Button button = new Button(text); button.getStyleClass().add("secondary-button"); return button; }
    private static Label sectionLabel(String text) { Label label = new Label(text); label.getStyleClass().add("section-kicker"); return label; }
    private static String rootMessage(Throwable error) {
        Throwable value = error; while (value.getCause() != null) value = value.getCause();
        return value.getMessage() == null ? value.getClass().getSimpleName() : value.getMessage();
    }

    private static final class ChapterCell extends ListCell<MangaDexReaderService.Chapter> {
        @Override protected void updateItem(MangaDexReaderService.Chapter chapter, boolean empty) {
            super.updateItem(chapter, empty);
            if (empty || chapter == null) { setText(null); setGraphic(null); return; }
            Label name = new Label(chapter.toString()); name.getStyleClass().add("reader-chapter-title"); name.setWrapText(true);
            String detail = (chapter.volume().isBlank() ? "" : "Vol. " + chapter.volume() + " · ")
                    + chapter.pages() + " pages · " + chapter.group();
            Label meta = new Label(detail); meta.getStyleClass().add("reader-chapter-meta"); meta.setWrapText(true);
            setGraphic(new VBox(3, name, meta)); setText(null);
        }
    }
}
