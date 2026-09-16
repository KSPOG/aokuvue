package app.kspani.ui;

import app.kspani.app.AppContext;
import app.kspani.domain.AniMedia;
import app.kspani.domain.MediaType;
import app.kspani.player.PlaybackProgressRepository;
import app.kspani.player.PlayerSession;
import app.kspani.source.AnimeSource;
import app.kspani.source.EpisodeLoadResult;
import app.kspani.source.SourceEpisode;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Adds a playback-backed Continue Watching entry to every anime details page.
 *
 * The entry is intentionally series-agnostic: it uses AniList media id + local playback history,
 * resolves the stored episode through the currently available provider stack, and lets
 * PlayerController restore the saved timestamp.
 */
public final class DetailsResumeEnhancer {
    private static final String ENHANCED = DetailsResumeEnhancer.class.getName() + ".enhanced";
    private static final long COMPLETED_TOLERANCE_MS = 1_000L;

    private final MainWindow root;
    private final AppContext app;
    private StackPane contentHost;

    private DetailsResumeEnhancer(MainWindow root, AppContext app) {
        this.root = root;
        this.app = app;
    }

    public static void install(MainWindow root, AppContext app) {
        if (root == null || app == null) return;
        DetailsResumeEnhancer enhancer = new DetailsResumeEnhancer(root, app);
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
        if (hero == null || !(hero.getParent() instanceof VBox body)) return;
        if (Boolean.TRUE.equals(body.getProperties().get(ENHANCED))) return;

        AniMedia media = currentMedia();
        if (media == null || media.type() != MediaType.ANIME) return;
        body.getProperties().put(ENHANCED, Boolean.TRUE);

        PlaybackProgressRepository.ContinueEntry entry;
        try {
            entry = app.playbackProgress().latestForMedia(media.id()).orElse(null);
        } catch (RuntimeException error) {
            System.err.println("[Aokuvue][Resume] Unable to read playback history: " + error.getMessage());
            return;
        }
        if (!isResumable(entry)) return;

        Node resume = resumeCard(media, entry);
        int heroIndex = body.getChildren().indexOf(hero);
        body.getChildren().add(Math.max(0, heroIndex + 1), resume);
    }

    private Node resumeCard(AniMedia media, PlaybackProgressRepository.ContinueEntry entry) {
        Label kicker = new Label("A O K U V U E   /   C O N T I N U E   W A T C H I N G");
        kicker.getStyleClass().add("section-kicker");

        Label title = new Label("Resume Episode " + entry.episodeNumber());
        title.getStyleClass().add("section-title");

        String detailText = "Continue " + media.title() + " from " + clock(entry.positionMs());
        if (entry.durationMs() > 0) detailText += " of " + clock(entry.durationMs());
        Label detail = new Label(detailText);
        detail.setWrapText(true);
        detail.getStyleClass().add("source-status");

        double fraction = entry.durationMs() > 0
                ? Math.max(0.0, Math.min(1.0, (double) entry.positionMs() / entry.durationMs()))
                : 0.0;
        ProgressBar progress = new ProgressBar(fraction);
        progress.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(progress, Priority.ALWAYS);

        int percent = (int) Math.round(fraction * 100.0);
        Label progressText = new Label(entry.durationMs() > 0 ? percent + "%" : "Saved position");
        progressText.getStyleClass().add("player-meta");

        Button resume = new Button("▶  Resume Episode " + entry.episodeNumber());
        resume.getStyleClass().add("primary-button");
        resume.setFocusTraversable(false);

        Label state = new Label();
        state.getStyleClass().add("source-status");
        state.setVisible(false);
        state.setManaged(false);

        Runnable action = () -> resumeEpisode(media, entry, resume, state);
        resume.setOnAction(event -> action.run());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox top = new HBox(14, new VBox(4, kicker, title, detail), spacer, resume);
        top.setAlignment(Pos.CENTER_LEFT);

        HBox progressRow = new HBox(10, progress, progressText);
        progressRow.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(11, top, progressRow, state);
        card.getStyleClass().add("info-card");
        card.setPadding(new Insets(16, 18, 16, 18));
        card.setCursor(Cursor.HAND);
        card.setOnMouseClicked(event -> {
            if (event.getTarget() instanceof Button) return;
            action.run();
        });

        VBox wrapper = new VBox(card);
        wrapper.setPadding(new Insets(16, AokuvueTheme.PAGE_GUTTER, 8, AokuvueTheme.PAGE_GUTTER));
        return wrapper;
    }

    private void resumeEpisode(
            AniMedia media,
            PlaybackProgressRepository.ContinueEntry entry,
            Button button,
            Label state
    ) {
        if (button.isDisabled()) return;
        button.setDisable(true);
        showState(state, "Resolving Episode " + entry.episodeNumber() + "…");

        CompletableFuture<EpisodeLoadResult> load;
        boolean savedSourceAvailable = entry.sourceId() != null && !entry.sourceId().isBlank()
                && app.sources().get(entry.sourceId()).filter(AnimeSource::isConfigured).isPresent();
        if (savedSourceAvailable) {
            load = app.episodes().loadEpisodes(media, entry.sourceId(), false);
        } else {
            load = app.episodes().loadEpisodes(media, false);
        }

        load.whenComplete((loaded, loadError) -> {
            if (loadError != null || loaded == null) {
                Platform.runLater(() -> fail(button, state, loadError == null ? "Unable to load episodes." : rootMessage(loadError)));
                return;
            }

            SourceEpisode episode = findEpisode(loaded.episodes(), entry.episodeNumber());
            if (episode == null) {
                Platform.runLater(() -> fail(button, state,
                        "Episode " + entry.episodeNumber() + " is not available from the current playback sources."));
                return;
            }

            app.episodes().resolveEpisode(media, loaded, episode).whenComplete((resolved, resolveError) -> {
                if (resolveError != null || resolved == null) {
                    Platform.runLater(() -> fail(button, state,
                            resolveError == null ? "Unable to resolve the saved episode." : rootMessage(resolveError)));
                    return;
                }

                Platform.runLater(() -> {
                    try {
                        Method showPlayer = MainWindow.class.getDeclaredMethod("showPlayer", PlayerSession.class);
                        showPlayer.setAccessible(true);
                        showPlayer.invoke(root, new PlayerSession(media, loaded, resolved));
                    } catch (ReflectiveOperationException error) {
                        fail(button, state, "Unable to open the player: " + rootMessage(error));
                    }
                });
            });
        });
    }

    private static SourceEpisode findEpisode(List<SourceEpisode> episodes, String storedNumber) {
        if (episodes == null || episodes.isEmpty() || storedNumber == null) return null;
        for (SourceEpisode episode : episodes) {
            if (episode.number() != null && episode.number().equalsIgnoreCase(storedNumber)) return episode;
        }
        double target = numeric(storedNumber);
        if (Double.isNaN(target)) return null;
        for (SourceEpisode episode : episodes) {
            double candidate = episode.numericNumber();
            if (!Double.isNaN(candidate) && Math.abs(candidate - target) < 0.0001) return episode;
        }
        return null;
    }

    private static double numeric(String number) {
        try {
            return Double.parseDouble(number.replaceAll("[^0-9.]", ""));
        } catch (RuntimeException ignored) {
            return Double.NaN;
        }
    }

    private static boolean isResumable(PlaybackProgressRepository.ContinueEntry entry) {
        if (entry == null || entry.positionMs() <= 0) return false;
        if (entry.durationMs() <= 0) return true;
        return entry.positionMs() < entry.durationMs() - COMPLETED_TOLERANCE_MS;
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

    private static void showState(Label state, String text) {
        state.setText(text);
        state.setManaged(true);
        state.setVisible(true);
    }

    private static void fail(Button button, Label state, String message) {
        button.setDisable(false);
        showState(state, message == null || message.isBlank() ? "Unable to resume playback." : message);
    }

    private static String clock(long millis) {
        long total = Math.max(0L, millis) / 1_000L;
        long hours = total / 3_600L;
        long minutes = (total % 3_600L) / 60L;
        long seconds = total % 60L;
        return hours > 0
                ? String.format("%d:%02d:%02d", hours, minutes, seconds)
                : String.format("%02d:%02d", minutes, seconds);
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
