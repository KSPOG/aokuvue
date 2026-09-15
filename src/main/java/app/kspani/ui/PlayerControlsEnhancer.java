package app.kspani.ui;

import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * Repairs the native player's bottom control bar so independent control groups never overlap.
 *
 * MainWindow builds player content lazily. This enhancer observes the scene graph, replaces the
 * legacy layered StackPane with a real responsive layout, and switches to two rows when the
 * available player width is too small for the media, transport, and selector groups side by side.
 */
public final class PlayerControlsEnhancer {
    private static final String OBSERVED = PlayerControlsEnhancer.class.getName() + ".observed";
    private static final String CONFIGURED = PlayerControlsEnhancer.class.getName() + ".configured";
    private static final double COMPACT_BREAKPOINT = 1_280.0;

    private PlayerControlsEnhancer() {}

    public static void install(Parent root) {
        if (root == null) return;
        observe(root);
    }

    private static void observe(Node node) {
        if (node instanceof VBox box && box.getStyleClass().contains("player-controls")) {
            Platform.runLater(() -> configurePlayerControls(box));
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

    private static void configurePlayerControls(VBox controls) {
        if (Boolean.TRUE.equals(controls.getProperties().get(CONFIGURED))) return;
        if (controls.getChildren().size() < 2) return;

        Node legacyRow = controls.getChildren().get(1);
        if (!(legacyRow instanceof StackPane stack) || stack.getChildren().size() < 3) return;

        Node mediaIdentity = stack.getChildren().get(0);
        Node transport = stack.getChildren().get(1);
        Node selectors = stack.getChildren().get(2);
        stack.getChildren().clear();

        prepareGroup(mediaIdentity);
        prepareGroup(transport);
        prepareGroup(selectors);

        VBox responsive = new VBox(8);
        responsive.getStyleClass().add("player-controls-responsive");
        responsive.setFillWidth(true);
        controls.getChildren().set(1, responsive);
        controls.getProperties().put(CONFIGURED, Boolean.TRUE);

        final boolean[] compact = {false};
        Runnable relayout = () -> {
            double width = controls.getWidth();
            boolean shouldCompact = width > 0 && width < COMPACT_BREAKPOINT;
            if (!responsive.getChildren().isEmpty() && compact[0] == shouldCompact) return;
            compact[0] = shouldCompact;
            if (shouldCompact) {
                layoutCompact(responsive, mediaIdentity, transport, selectors);
            } else {
                layoutWide(responsive, mediaIdentity, transport, selectors);
            }
        };

        controls.widthProperty().addListener((observable, oldWidth, newWidth) -> relayout.run());
        Platform.runLater(relayout);
    }

    private static void prepareGroup(Node node) {
        if (node instanceof Region region) region.setMinWidth(0);
        if (node instanceof HBox box) {
            box.setFillHeight(true);
            for (Node child : box.getChildren()) {
                if (child instanceof Region region && !child.getStyleClass().contains("player-primary-control")) {
                    region.setMinWidth(0);
                }
            }
        }
    }

    private static void layoutWide(VBox responsive, Node mediaIdentity, Node transport, Node selectors) {
        detach(mediaIdentity);
        detach(transport);
        detach(selectors);

        BorderPane row = new BorderPane();
        row.getStyleClass().add("player-controls-wide-row");
        row.setMinWidth(0);
        row.setLeft(mediaIdentity);
        row.setCenter(transport);
        row.setRight(selectors);
        BorderPane.setAlignment(mediaIdentity, Pos.CENTER_LEFT);
        BorderPane.setAlignment(transport, Pos.CENTER);
        BorderPane.setAlignment(selectors, Pos.CENTER_RIGHT);
        BorderPane.setMargin(transport, new Insets(0, 16, 0, 16));

        responsive.getChildren().setAll(row);
    }

    private static void layoutCompact(VBox responsive, Node mediaIdentity, Node transport, Node selectors) {
        detach(mediaIdentity);
        detach(transport);
        detach(selectors);

        BorderPane primary = new BorderPane();
        primary.getStyleClass().add("player-controls-compact-primary");
        primary.setMinWidth(0);
        primary.setLeft(mediaIdentity);
        primary.setRight(transport);
        BorderPane.setAlignment(mediaIdentity, Pos.CENTER_LEFT);
        BorderPane.setAlignment(transport, Pos.CENTER_RIGHT);
        BorderPane.setMargin(transport, new Insets(0, 0, 0, 14));

        if (selectors instanceof HBox selectorBox) {
            selectorBox.setAlignment(Pos.CENTER_RIGHT);
            selectorBox.setMaxWidth(Double.MAX_VALUE);
        }

        StackPane selectorRow = new StackPane(selectors);
        selectorRow.getStyleClass().add("player-controls-compact-selectors");
        selectorRow.setAlignment(Pos.CENTER_RIGHT);
        selectorRow.setMinWidth(0);

        responsive.getChildren().setAll(primary, selectorRow);
    }

    private static void detach(Node node) {
        if (node.getParent() instanceof Pane pane) pane.getChildren().remove(node);
    }
}
