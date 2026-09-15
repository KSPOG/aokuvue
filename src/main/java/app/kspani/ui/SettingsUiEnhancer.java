package app.kspani.ui;

import app.kspani.app.AppVersion;
import app.kspani.config.AppConfig;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.util.List;
import java.util.function.Consumer;

/**
 * Small presentation upgrades for settings controls that are created lazily by MainWindow.
 *
 * Settings are rebuilt when the page is opened, so this enhancer runs after JavaFX action events
 * and decorates those controls without coupling playback/runtime metadata to MainWindow.
 */
public final class SettingsUiEnhancer {
    private static final String WATCH_THRESHOLD_ENHANCED =
            SettingsUiEnhancer.class.getName() + ".watchThresholdEnhanced";
    private static final String APPEARANCE_VERSION_ENHANCED =
            SettingsUiEnhancer.class.getName() + ".appearanceVersionEnhanced";
    private static final String SUBTITLE_DRAG_SETTING_ENHANCED =
            SettingsUiEnhancer.class.getName() + ".subtitleDragSettingEnhanced";

    private SettingsUiEnhancer() {}

    public static void install(Parent root) {
        install(root, null, null);
    }

    public static void install(Parent root, AppConfig config, Consumer<String> openUrl) {
        if (root == null) return;
        Runnable refresh = () -> enhanceTree(root, config, openUrl);
        Platform.runLater(refresh);
        root.addEventHandler(ActionEvent.ACTION, event -> Platform.runLater(refresh));
    }

    private static void enhanceTree(Parent parent, AppConfig config, Consumer<String> openUrl) {
        // Enhancement can replace a slider with a row, so iterate over a stable snapshot.
        for (Node child : List.copyOf(parent.getChildrenUnmodifiable())) {
            if (child instanceof Slider slider && isWatchThreshold(slider)) {
                enhanceWatchThreshold(slider);
            }

            if (child instanceof VBox box) {
                enhancePlayerSettingsCard(box, config);
            }

            if (child instanceof ScrollPane scrollPane && scrollPane.getContent() instanceof Parent content) {
                enhanceTree(content, config, openUrl);
            }

            if (child instanceof TabPane tabPane) {
                enhanceAppearanceVersion(tabPane);
                // Tab content is not guaranteed to be present in Parent#getChildrenUnmodifiable
                // until its tab is selected. Traverse every tab explicitly so Playback is enhanced
                // even while Appearance is the selected tab.
                for (Tab tab : tabPane.getTabs()) {
                    if (tab.getContent() instanceof Parent tabContent) {
                        enhanceTree(tabContent, config, openUrl);
                    }
                }
            }

            if (child instanceof Parent nested) enhanceTree(nested, config, openUrl);
        }
    }

    private static boolean isWatchThreshold(Slider slider) {
        return Math.abs(slider.getMin() - 0.5) < 0.0001
                && Math.abs(slider.getMax() - 1.0) < 0.0001;
    }

    private static void enhanceWatchThreshold(Slider slider) {
        if (Boolean.TRUE.equals(slider.getProperties().get(WATCH_THRESHOLD_ENHANCED))) return;
        slider.getProperties().put(WATCH_THRESHOLD_ENHANCED, Boolean.TRUE);

        slider.setShowTickLabels(true);
        slider.setShowTickMarks(true);
        slider.setMajorTickUnit(0.10);
        slider.setBlockIncrement(0.01);
        slider.setLabelFormatter(new StringConverter<>() {
            @Override public String toString(Double value) {
                return Math.round(value * 100.0) + "%";
            }

            @Override public Double fromString(String text) {
                if (text == null || text.isBlank()) return slider.getValue();
                try {
                    return clampPercent(Integer.parseInt(text.replace("%", "").trim())) / 100.0;
                } catch (NumberFormatException ignored) {
                    return slider.getValue();
                }
            }
        });

        int initialPercent = clampPercent((int) Math.round(slider.getValue() * 100.0));
        SpinnerValueFactory.IntegerSpinnerValueFactory values =
                new SpinnerValueFactory.IntegerSpinnerValueFactory(50, 100, initialPercent, 1);
        Spinner<Integer> exactPercent = new Spinner<>();
        exactPercent.setValueFactory(values);
        exactPercent.setEditable(true);
        exactPercent.setPrefWidth(88);
        exactPercent.setTooltip(new Tooltip("Exact watched threshold (50–100%)."));

        Label percentSign = new Label("%");
        percentSign.getStyleClass().add("field-label");
        Label hint = new Label(
                "The episode is flagged as watched when playback reaches this percentage. "
                        + "Use the number field for an exact value.");
        hint.setWrapText(true);
        hint.getStyleClass().add("source-status");

        final boolean[] syncing = {false};
        slider.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (syncing[0]) return;
            int percent = clampPercent((int) Math.round(newValue.doubleValue() * 100.0));
            if (values.getValue() != percent) {
                syncing[0] = true;
                values.setValue(percent);
                syncing[0] = false;
            }
        });
        values.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (syncing[0] || newValue == null) return;
            syncing[0] = true;
            slider.setValue(clampPercent(newValue) / 100.0);
            syncing[0] = false;
        });
        exactPercent.getEditor().setOnAction(event -> commitEditor(exactPercent, values));
        exactPercent.focusedProperty().addListener((observable, oldValue, focused) -> {
            if (!focused) commitEditor(exactPercent, values);
        });

        HBox row = new HBox(10, slider, exactPercent, percentSign);
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(slider, Priority.ALWAYS);
        slider.setMaxWidth(Double.MAX_VALUE);

        if (slider.getParent() instanceof VBox field) {
            if (!field.getChildren().isEmpty() && field.getChildren().get(0) instanceof Label label) {
                label.setText("Mark episode watched at");
            }
            int index = field.getChildren().indexOf(slider);
            if (index >= 0) {
                field.getChildren().set(index, row);
                field.getChildren().add(index + 1, hint);
            }
        }
    }

    private static void enhancePlayerSettingsCard(VBox card, AppConfig config) {
        if (config == null || Boolean.TRUE.equals(card.getProperties().get(SUBTITLE_DRAG_SETTING_ENHANCED))) return;
        if (!card.getStyleClass().contains("settings-card")) return;

        boolean playerCard = card.getChildren().stream()
                .filter(Label.class::isInstance)
                .map(Label.class::cast)
                .anyMatch(label -> "Player".equals(label.getText()));
        if (!playerCard) return;

        CheckBox draggable = new CheckBox("Allow subtitles to be dragged in the player");
        draggable.setSelected(config.getBoolean("player.subtitleDraggable", false));
        draggable.setTooltip(new Tooltip("When enabled, drag the visible subtitle text to reposition it."));
        draggable.setOnAction(event -> config.set("player.subtitleDraggable", Boolean.toString(draggable.isSelected())));

        Label hint = new Label(
                "Disabled keeps subtitles centered. When enabled, the last dragged position is remembered for future playback.");
        hint.setWrapText(true);
        hint.getStyleClass().add("source-status");

        VBox control = new VBox(6, draggable, hint);
        card.getChildren().add(control);
        card.getProperties().put(SUBTITLE_DRAG_SETTING_ENHANCED, Boolean.TRUE);
    }

    private static void enhanceAppearanceVersion(TabPane tabPane) {
        if (Boolean.TRUE.equals(tabPane.getProperties().get(APPEARANCE_VERSION_ENHANCED))) return;

        for (Tab tab : tabPane.getTabs()) {
            if (!"Appearance".equalsIgnoreCase(tab.getText())) continue;
            if (!(tab.getContent() instanceof VBox appearance)) return;

            Label kicker = new Label("AOKUVUE / ABOUT");
            kicker.getStyleClass().add("section-kicker");

            Label title = new Label("Application version");
            title.getStyleClass().add("section-title");

            Label version = new Label(AppVersion.display());
            version.getStyleClass().add("fact-value");

            Label detail = new Label("Current installed Aokuvue beta build.");
            detail.setWrapText(true);
            detail.getStyleClass().add("source-status");

            VBox versionCard = new VBox(9, kicker, title, version, detail);
            versionCard.getStyleClass().add("settings-card");
            versionCard.setPadding(new Insets(18));

            appearance.getChildren().addAll(versionCard, createSupportCard());
            tabPane.getProperties().put(APPEARANCE_VERSION_ENHANCED, Boolean.TRUE);
            return;
        }
    }

    private static VBox createSupportCard() {
        Label kicker = new Label("AOKUVUE / SUPPORT");
        kicker.getStyleClass().add("section-kicker");

        Label title = new Label("Support Aokuvue");
        title.getStyleClass().add("section-title");

        Label comingSoon = new Label("Donation support is coming soon");
        comingSoon.getStyleClass().add("fact-value");

        Label detail = new Label(
                "We're preparing a simple and secure way for viewers to support Aokuvue's continued "
                        + "development, maintenance, and future features. More information will be available soon.");
        detail.setWrapText(true);
        detail.getStyleClass().add("source-status");

        VBox card = new VBox(10, kicker, title, comingSoon, detail);
        card.getStyleClass().add("settings-card");
        card.setPadding(new Insets(18));
        return card;
    }

    private static void commitEditor(
            Spinner<Integer> spinner,
            SpinnerValueFactory.IntegerSpinnerValueFactory values
    ) {
        String text = spinner.getEditor().getText();
        try {
            int percent = clampPercent(Integer.parseInt(text.replace("%", "").trim()));
            values.setValue(percent);
            spinner.getEditor().setText(Integer.toString(percent));
        } catch (RuntimeException ignored) {
            spinner.getEditor().setText(Integer.toString(values.getValue()));
        }
    }

    private static int clampPercent(int value) {
        return Math.max(50, Math.min(100, value));
    }
}
