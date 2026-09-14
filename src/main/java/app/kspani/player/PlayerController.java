package app.kspani.player;

import app.kspani.anilist.AniListClient;
import app.kspani.anilist.AniListListService;
import app.kspani.config.AppConfig;
import app.kspani.source.PlaybackSource;
import javafx.application.Platform;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.util.Duration;

import java.util.function.Consumer;

/**
 * Player responsibility mirrors Saikou's PlayerViewModel: load resolved media, persist resume,
 * enforce watch threshold, and update AniList independently from source matching.
 */
public final class PlayerController implements AutoCloseable {
    private final AppConfig config;
    private PlayerSettings settings;
    private final PlaybackProgressRepository progress;
    private final AniListClient anilist;
    private final AniListListService listService;

    private MediaPlayer mediaPlayer;
    private HttpMediaRelay mediaRelay;
    private PlayerSession session;
    private boolean watchedSent;
    private long lastSavedAt;
    private Consumer<String> status = ignored -> {};

    public PlayerController(
            AppConfig config,
            PlaybackProgressRepository progress,
            AniListClient anilist,
            AniListListService listService
    ) {
        this.config = config;
        this.settings = PlayerSettings.load(config);
        this.progress = progress;
        this.anilist = anilist;
        this.listService = listService;
    }

    public void setStatusConsumer(Consumer<String> consumer) {
        status = consumer == null ? ignored -> {} : consumer;
    }

    public MediaPlayer load(PlayerSession newSession) {
        disposeCurrent();
        this.session = newSession;
        PlaybackSource source = newSession.playback().selectedVideo();
        status.accept("Opening Episode " + newSession.playback().episode().number() + "…");

        // Signed provider/CDN media often needs the same Referer/User-Agent used by the embedded
        // browser. JavaFX MediaPlayer cannot attach arbitrary HTTP headers and WebKit may fail to
        // connect to GoogleVideo even when the signed URL is valid. Route header-bearing sources
        // through a loopback byte-range relay; JavaFX then only talks to 127.0.0.1.
        String mediaUri = source.uri().toString();
        if (!source.headers().isEmpty()) {
            mediaRelay = HttpMediaRelay.start(source.uri(), source.headers(), source.container());
            mediaUri = mediaRelay.localUri().toString();
            status.accept("Opening relayed stream for Episode " + newSession.playback().episode().number() + "…");
        }

        final String resolvedMediaUri = mediaUri;
        Media media = new Media(resolvedMediaUri);
        media.errorProperty().addListener((obs, oldError, newError) -> {
            if (newError != null) {
                System.err.println("[Aokuvue][Media] " + newError.getType() + ": " + newError.getMessage()
                        + " source=" + resolvedMediaUri);
            }
        });
        MediaPlayer player;
        try {
            player = new MediaPlayer(media);
        } catch (RuntimeException failure) {
            if (mediaRelay != null) {
                mediaRelay.close();
                mediaRelay = null;
            }
            throw failure;
        }
        this.mediaPlayer = player;
        player.setRate(settings.defaultSpeed());

        var saved = progress.load(
                newSession.media().id(),
                newSession.playback().source().descriptor().id(),
                newSession.playback().episode().number()
        );
        watchedSent = saved.watched();

        player.setOnReady(() -> {
            long duration = (long) player.getTotalDuration().toMillis();
            boolean resumed = false;
            if (saved.positionMs() > 0 && saved.positionMs() < duration - 15_000) {
                player.seek(Duration.millis(saved.positionMs()));
                resumed = true;
            }
            if (settings.autoPlay()) {
                status.accept((resumed ? "Resuming" : "Starting") + " Episode "
                        + newSession.playback().episode().number() + "…");
                player.play();
            } else {
                status.accept((resumed ? "Resumed" : "Ready") + " Episode "
                        + newSession.playback().episode().number() + ".");
            }
        });
        player.setOnPlaying(() -> status.accept("Playing Episode " + newSession.playback().episode().number() + "."));
        player.setOnPaused(() -> status.accept("Episode " + newSession.playback().episode().number() + " paused."));
        player.setOnStalled(() -> status.accept("Buffering Episode " + newSession.playback().episode().number() + "…"));
        player.setOnHalted(() -> status.accept("Playback halted for Episode " + newSession.playback().episode().number() + "."));

        player.currentTimeProperty().addListener((obs, oldValue, now) -> onProgress(now.toMillis(), player.getTotalDuration().toMillis()));
        player.setOnEndOfMedia(() -> {
            onProgress(player.getTotalDuration().toMillis(), player.getTotalDuration().toMillis());
            status.accept("Episode " + newSession.playback().episode().number() + " finished.");
        });
        player.setOnError(() -> {
            var error = player.getError();
            System.err.println("[Aokuvue][MediaPlayer] " + (error == null ? "Unknown" : error.getType() + ": " + error.getMessage()));
            status.accept("Player error: " + (error == null ? "Unknown" : error.getMessage()));
        });
        return player;
    }

    private void onProgress(double position, double duration) {
        if (session == null || duration <= 0 || Double.isNaN(duration)) return;
        long pos = (long) position;
        long dur = (long) duration;
        boolean threshold = settings.autoMarkWatched() && position / duration >= settings.watchPercentage();
        long now = System.currentTimeMillis();
        if (threshold || now - lastSavedAt >= 5_000) {
            progress.save(session.media().id(), session.playback().source().descriptor().id(), session.playback().episode().number(), pos, dur, threshold);
            lastSavedAt = now;
        }
        if (threshold && !watchedSent) {
            watchedSent = true;
            syncAniList();
        }
    }

    private void syncAniList() {
        if (session == null || !anilist.authenticated()) return;
        int episode;
        try {
            double n = session.playback().episode().numericNumber();
            if (Double.isNaN(n) || n < 1) return;
            episode = (int) Math.floor(n);
        } catch (Exception ignored) { return; }
        if (episode <= session.media().knownProgress()) return;
        listService.setProgress(session.media(), episode).whenComplete((entry, error) -> Platform.runLater(() -> {
            if (error != null) status.accept("Watched locally; AniList sync failed: " + root(error));
            else status.accept("AniList progress updated to Episode " + entry.progress() + ".");
        }));
    }

    public void markWatchedNow() {
        if (session == null) return;
        progress.markWatched(
                session.media().id(),
                session.playback().source().descriptor().id(),
                session.playback().episode().number()
        );
        watchedSent = false;
        syncAniList();
        watchedSent = true;
        status.accept("Episode " + session.playback().episode().number() + " marked watched.");
    }

    public MediaPlayer player() { return mediaPlayer; }
    public PlayerSession session() { return session; }
    public PlayerSettings settings() { return settings; }
    public void reloadSettings() { settings = PlayerSettings.load(config); }

    public void playPause() {
        if (mediaPlayer == null) return;
        if (mediaPlayer.getStatus() == MediaPlayer.Status.PLAYING) mediaPlayer.pause(); else mediaPlayer.play();
    }

    public void seekRelative(int seconds) {
        if (mediaPlayer == null) return;
        double target = mediaPlayer.getCurrentTime().toMillis() + seconds * 1000.0;
        double max = mediaPlayer.getTotalDuration().toMillis();
        mediaPlayer.seek(Duration.millis(Math.max(0, Math.min(target, max))));
    }

    private void disposeCurrent() {
        if (mediaPlayer != null) {
            try {
                if (session != null && mediaPlayer.getTotalDuration() != null) {
                    progress.save(session.media().id(), session.playback().source().descriptor().id(), session.playback().episode().number(),
                            (long) mediaPlayer.getCurrentTime().toMillis(), (long) mediaPlayer.getTotalDuration().toMillis(), watchedSent);
                }
            } catch (Exception ignored) {}
            mediaPlayer.stop();
            mediaPlayer.dispose();
            mediaPlayer = null;
        }
        if (mediaRelay != null) {
            try { mediaRelay.close(); } catch (Exception ignored) {}
            mediaRelay = null;
        }
    }

    @Override public void close() { disposeCurrent(); }

    private static String root(Throwable e) {
        Throwable c=e; while(c.getCause()!=null)c=c.getCause(); return c.getMessage()==null?c.getClass().getSimpleName():c.getMessage();
    }
}
