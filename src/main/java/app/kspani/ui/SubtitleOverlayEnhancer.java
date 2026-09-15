package app.kspani.ui;

import app.kspani.config.AppConfig;
import javafx.collections.ListChangeListener;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.input.MouseButton;
import javafx.scene.text.TextAlignment;

/**
 * Keeps subtitle text centered and optionally allows the player subtitle overlay to be repositioned.
 *
 * MainWindow rebuilds player/settings content lazily, so this enhancer observes the scene graph and
 * applies the behavior whenever a subtitle overlay is attached.
 */
public final class SubtitleOverlayEnhancer {
    private static final String OBSERVED = SubtitleOverlayEnhancer.class.getName() + ".observed";
    private static final String CONFIGURED = SubtitleOverlayEnhancer.class.getName() + ".configured";

    private static final String DRAGGABLE_KEY = "player.subtitleDraggable";
    private static final String OFFSET_X_KEY = "player.subtitleOffsetX";
    private static final String OFFSET_Y_KEY = "player.subtitleOffsetY";

    private SubtitleOverlayEnhancer() {}

    public static void install(Parent root, AppConfig config) {
        if (root == null || config == null) return;
        observe(root, config);
    }

    private static void observe(Node node, AppConfig config) {
        if (node instanceof Label label && label.getStyleClass().contains("subtitle-overlay")) {
            configureSubtitleLabel(label, config);
        }

        if (!(node instanceof Parent parent)) return;

        for (Node child : parent.getChildrenUnmodifiable()) {
            observe(child, config);
        }

        if (Boolean.TRUE.equals(parent.getProperties().get(OBSERVED))) return;
        parent.getProperties().put(OBSERVED, Boolean.TRUE);
        parent.getChildrenUnmodifiable().addListener((ListChangeListener<Node>) change -> {
            while (change.next()) {
                if (!change.wasAdded()) continue;
                for (Node child : change.getAddedSubList()) {
                    observe(child, config);
                }
            }
        });
    }

    private static void configureSubtitleLabel(Label label, AppConfig config) {
        label.setAlignment(Pos.CENTER);
        label.setTextAlignment(TextAlignment.CENTER);

        if (!isPlayerSubtitle(label)) return;
        if (Boolean.TRUE.equals(label.getProperties().get(CONFIGURED))) return;
        label.getProperties().put(CONFIGURED, Boolean.TRUE);

        boolean draggable = config.getBoolean(DRAGGABLE_KEY, false);
        if (!draggable) {
            label.setTranslateX(0);
            label.setTranslateY(0);
            label.setMouseTransparent(true);
            label.setCursor(Cursor.DEFAULT);
            return;
        }

        label.setTranslateX(config.getDouble(OFFSET_X_KEY, 0.0));
        label.setTranslateY(config.getDouble(OFFSET_Y_KEY, 0.0));
        label.setMouseTransparent(false);
        label.setCursor(Cursor.MOVE);

        double[] drag = new double[4];
        label.setOnMousePressed(event -> {
            if (event.getButton() != MouseButton.PRIMARY) return;
            drag[0] = event.getSceneX();
            drag[1] = event.getSceneY();
            drag[2] = label.getTranslateX();
            drag[3] = label.getTranslateY();
            event.consume();
        });
        label.setOnMouseDragged(event -> {
            if (!event.isPrimaryButtonDown()) return;
            label.setTranslateX(drag[2] + event.getSceneX() - drag[0]);
            label.setTranslateY(drag[3] + event.getSceneY() - drag[1]);
            event.consume();
        });
        label.setOnMouseReleased(event -> {
            if (event.getButton() != MouseButton.PRIMARY) return;
            config.set(OFFSET_X_KEY, Double.toString(label.getTranslateX()));
            config.set(OFFSET_Y_KEY, Double.toString(label.getTranslateY()));
            event.consume();
        });
    }

    private static boolean isPlayerSubtitle(Label label) {
        Node node = label.getParent();
        while (node != null) {
            if (node.getStyleClass().contains("video-stage")) return true;
            node = node.getParent();
        }
        return false;
    }
}
