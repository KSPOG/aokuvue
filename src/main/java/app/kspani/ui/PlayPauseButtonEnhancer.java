package app.kspani.ui;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.collections.ListChangeListener;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;

/**
 * Keeps the native player's play/pause button synchronized with the actual JavaFX MediaPlayer state.
 *
 * The player UI is created lazily, so this enhancer observes the scene graph and attaches to every
 * native player button as it appears. Listening to MediaPlayer.Status also covers autoplay, keyboard
 * shortcuts, buffering/stalls, and any state changes that did not originate from the button itself.
 */
public final class PlayPauseButtonEnhancer {
    private static final String OBSERVED = PlayPauseButtonEnhancer.class.getName() + ".observed";
    private static final String CONFIGURED = PlayPauseButtonEnhancer.class.getName() + ".configured";

    private PlayPauseButtonEnhancer() {}

    public static void install(Parent root) {
        if (root == null) return;
        observe(root);
    }

    private static void observe(Node node) {
        if (node instanceof Button button && button.getStyleClass().contains("player-primary-control")) {
            Platform.runLater(() -> configure(button));
        }

        if (!(node instanceof Parent parent)) return;
        for (Node child : parent.getChildrenUnmodifiable()) observe(child);

        if (Boolean.TRUE.equals(parent.getProperties().get(OBSERVED))) return;
        parent.getProperties().put(OBSERVED, Boolean.TRUE);
        parent.getChildrenUnmodifiable().addListener((ListChangeListener<Node>) change -> {
            while (change.next()) {
                if (!change.wasAdded()) continue;
                for (Node child : change.getAddedSubList()) observe(child);
            }
        });
    }

    private static void configure(Button button) {
        if (button.getParent() == null || Boolean.TRUE.equals(button.getProperties().get(CONFIGURED))) return;

        Parent playerScreen = findAncestor(button, "player-screen");
        if (playerScreen == null) return;
        MediaView mediaView = findMediaView(playerScreen);
        if (mediaView == null) return;

        button.getProperties().put(CONFIGURED, Boolean.TRUE);
        Tooltip tooltip = new Tooltip("Play");
        button.setTooltip(tooltip);
        button.setFocusTraversable(false);

        final MediaPlayer[] observedPlayer = {null};
        ChangeListener<MediaPlayer.Status> statusListener = (observable, oldStatus, newStatus) ->
                updateButton(button, tooltip, newStatus);

        java.util.function.Consumer<MediaPlayer> attach = player -> {
            if (observedPlayer[0] != null) {
                observedPlayer[0].statusProperty().removeListener(statusListener);
            }
            observedPlayer[0] = player;
            if (player == null) {
                updateButton(button, tooltip, null);
                return;
            }
            player.statusProperty().addListener(statusListener);
            updateButton(button, tooltip, player.getStatus());
        };

        mediaView.mediaPlayerProperty().addListener((observable, oldPlayer, newPlayer) -> attach.accept(newPlayer));
        attach.accept(mediaView.getMediaPlayer());
    }

    private static void updateButton(Button button, Tooltip tooltip, MediaPlayer.Status status) {
        boolean activePlayback = status == MediaPlayer.Status.PLAYING || status == MediaPlayer.Status.STALLED;
        String action = activePlayback ? "Pause" : "Play";
        button.setText(activePlayback ? "⏸" : "▶");
        button.setAccessibleText(action);
        tooltip.setText(action);
    }

    private static Parent findAncestor(Node node, String styleClass) {
        Node current = node;
        while (current != null) {
            if (current instanceof Parent parent && current.getStyleClass().contains(styleClass)) return parent;
            current = current.getParent();
        }
        return null;
    }

    private static MediaView findMediaView(Node node) {
        if (node instanceof MediaView mediaView) return mediaView;
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                MediaView found = findMediaView(child);
                if (found != null) return found;
            }
        }
        return null;
    }
}
