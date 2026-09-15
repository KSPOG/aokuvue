package app.kspani.ui;

import app.kspani.config.AppConfig;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.text.TextAlignment;

/**
 * Keeps subtitle text centered and optionally allows the player subtitle overlay to be repositioned.
 *
 * MainWindow rebuilds player/settings content lazily, so this enhancer observes the scene graph and
 * applies the behavior whenever a subtitle overlay or native player control bar is attached.
 */
public final class SubtitleOverlayEnhancer {
    private static final String OBSERVED = SubtitleOverlayEnhancer.class.getName() + ".observed";
    private static final String CONFIGURED = SubtitleOverlayEnhancer.class.getName() + ".configured";
    private static final String CENTER_CONTROL_ADDED = SubtitleOverlayEnhancer.class.getName() + ".centerControlAdded";

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

        if (node instanceof HBox box && isPlayerSelectorRow(box)) {
            // Do not mutate a JavaFX child list while its change listener is being traversed.
            Platform.runLater(() -> addCenterSubtitleControl(box, config));
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

    private static boolean isPlayerSelectorRow(HBox box) {
        if (!hasAncestorStyle(box, "player-controls")) return false;
        boolean cc = false;
        boolean speed = false;
        boolean volume = false;
        for (Node child : box.getChildren()) {
            if (!(child instanceof Label label)) continue;
            if ("CC".equals(label.getText())) cc = true;
            else if ("Speed".equals(label.getText())) speed = true;
            else if ("Vol".equals(label.getText())) volume = true;
        }
        return cc && speed && volume;
    }

    private static void addCenterSubtitleControl(HBox selectors, AppConfig config) {
        if (selectors.getParent() == null
                || Boolean.TRUE.equals(selectors.getProperties().get(CENTER_CONTROL_ADDED))) return;

        Button center = new Button("Center subs");
        center.getStyleClass().add("secondary-button");
        center.setFocusTraversable(false);
        center.setTooltip(new Tooltip("Reset subtitles to the default bottom-center position."));
        center.setOnAction(event -> {
            config.set(OFFSET_X_KEY, "0");
            config.set(OFFSET_Y_KEY, "0");

            Parent playerScreen = findAncestor(selectors, "player-screen");
            if (playerScreen != null) centerSubtitleLabels(playerScreen);
        });

        int ccIndex = -1;
        for (int i = 0; i < selectors.getChildren().size(); i++) {
            Node child = selectors.getChildren().get(i);
            if (child instanceof Label label && "CC".equals(label.getText())) {
                ccIndex = i;
                break;
            }
        }
        // CC label is followed by the subtitle selector. Put Center subs immediately after it.
        int insertAt = ccIndex < 0 ? 0 : Math.min(selectors.getChildren().size(), ccIndex + 2);
        selectors.getChildren().add(insertAt, center);
        selectors.getProperties().put(CENTER_CONTROL_ADDED, Boolean.TRUE);
    }

    private static void centerSubtitleLabels(Node node) {
        if (node instanceof Label label
                && label.getStyleClass().contains("subtitle-overlay")
                && isPlayerSubtitle(label)) {
            label.setTranslateX(0);
            label.setTranslateY(0);
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) centerSubtitleLabels(child);
        }
    }

    private static Parent findAncestor(Node node, String styleClass) {
        Node current = node;
        while (current != null) {
            if (current instanceof Parent parent && current.getStyleClass().contains(styleClass)) return parent;
            current = current.getParent();
        }
        return null;
    }

    private static boolean hasAncestorStyle(Node node, String styleClass) {
        return findAncestor(node, styleClass) != null;
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
