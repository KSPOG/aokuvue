package app.kspani.ui;

import app.kspani.app.AppVersion;
import app.kspani.config.AppConfig;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
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

    private static final List<SupportOption> SUPPORT_OPTIONS = List.of(
            new SupportOption("€2", "support.eur2Url", false),
            new SupportOption("€5", "support.eur5Url", false),
            new SupportOption("€10", "support.eur10Url", false),
            new SupportOption("€25", "support.eur25Url", false),
            new SupportOption("Custom amount", "support.customUrl", true)
    );

    private record SupportOption(String label, String configKey, boolean primary) {}

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

            if (child instanceof ScrollPane scrollPane && scrollPane.getContent() instanceof Parent content) {
                enhanceTree(content, config, openUrl);
            }

            if (child instanceof TabPane tabPane) {
                enhanceAppearanceVersion(tabPane, config, openUrl);
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

    private static void enhanceAppearanceVersion(
            TabPane tabPane,
            AppConfig config,
            Consumer<String> openUrl
    ) {
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

            appearance.getChildren().addAll(versionCard, createSupportCard(config, openUrl));
            tabPane.getProperties().put(APPEARANCE_VERSION_ENHANCED, Boolean.TRUE);
            return;
        }
    }

    private static VBox createSupportCard(AppConfig config, Consumer<String> openUrl) {
        Label kicker = new Label("AOKUVUE / SUPPORT");
        kicker.getStyleClass().add("section-kicker");

        Label title = new Label("Support Aokvue");
        title.getStyleClass().add("section-title");

        Label detail = new Label(
                "If you enjoy Aokvue, you can optionally support continued development, maintenance, "
                        + "and new features. Choose a preset amount or use Custom amount.");
        detail.setWrapText(true);
        detail.getStyleClass().add("source-status");

        FlowPane amounts = new FlowPane(10, 10);
        amounts.setAlignment(Pos.CENTER_LEFT);
        amounts.setPrefWrapLength(560);

        boolean configured = false;
        for (SupportOption option : SUPPORT_OPTIONS) {
            String url = config == null ? "" : config.get(option.configKey());
            Button button = new Button(option.label());
            button.getStyleClass().add(option.primary() ? "primary-button" : "secondary-button");
            button.setMinWidth(option.primary() ? 136 : 72);

            if (url.isBlank() || openUrl == null) {
                button.setDisable(true);
                button.setTooltip(new Tooltip("This payment option is not configured in this build yet."));
            } else {
                configured = true;
                button.setOnAction(event -> openUrl.accept(url));
                button.setTooltip(new Tooltip(
                        option.primary()
                                ? "Open the secure checkout and enter any amount you prefer."
                                : "Open the secure checkout for a " + option.label() + " contribution."
                ));
            }
            amounts.getChildren().add(button);
        }

        Label paymentNote = new Label(
                configured
                        ? "Checkout opens in your browser. Payment details are handled by the configured payment provider, not by Aokvue."
                        : "Support checkout links have not been configured for this build yet."
        );
        paymentNote.setWrapText(true);
        paymentNote.getStyleClass().add("source-status");

        VBox card = new VBox(12, kicker, title, detail, amounts, paymentNote);
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
