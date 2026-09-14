package app.kspani.ui;

import app.kspani.app.AppUpdateService;
import javafx.animation.FadeTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/** Small non-modal notification shown when a newer Aokuvue build is available. */
public final class UpdateNotification extends VBox {
    private final Label message = new Label();
    private final Label detail = new Label();
    private final Button update = new Button("Update now");
    private final Button later = new Button("Later");
    private final ProgressIndicator progress = new ProgressIndicator();

    public UpdateNotification(AppUpdateService.UpdateInfo info, Runnable updateAction, Runnable dismissAction) {
        setSpacing(8);
        setPadding(new Insets(16, 18, 16, 18));
        setMaxWidth(410);
        setPrefWidth(390);
        setStyle("-fx-background-color: linear-gradient(to bottom right,#171322,#0d0c14);"
                + "-fx-background-radius: 10px;"
                + "-fx-border-radius: 10px;"
                + "-fx-border-color: rgba(142,124,255,.72);"
                + "-fx-effect: dropshadow(gaussian,rgba(0,0,0,.62),24,.25,0,8);");

        Label eyebrow = new Label("UPDATE AVAILABLE");
        eyebrow.setStyle("-fx-text-fill:#A99CFF;-fx-font-size:9px;-fx-font-weight:800;");

        message.setText("Aokuvue " + info.latestVersion() + " is available");
        message.setStyle("-fx-font-family:'Georgia';-fx-font-size:19px;-fx-text-fill:#F3EFFF;");

        detail.setText("You are running " + info.currentVersion()
                + ". Update through the Aokuvue Installer & Updater.");
        detail.setWrapText(true);
        detail.setStyle("-fx-text-fill:#B9B2CA;-fx-font-size:11px;");

        update.getStyleClass().add("primary-button");
        later.getStyleClass().add("secondary-button");
        update.setOnAction(event -> updateAction.run());
        later.setOnAction(event -> dismissAction.run());

        progress.setPrefSize(18, 18);
        progress.setMaxSize(18, 18);
        progress.setVisible(false);
        progress.setManaged(false);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actions = new HBox(9, progress, spacer, later, update);
        actions.setAlignment(Pos.CENTER_RIGHT);

        getChildren().addAll(eyebrow, message, detail, actions);
        setOpacity(0);
        FadeTransition fade = new FadeTransition(Duration.millis(260), this);
        fade.setFromValue(0);
        fade.setToValue(1);
        fade.play();
    }

    public void setUpdating() {
        update.setDisable(true);
        later.setDisable(true);
        progress.setVisible(true);
        progress.setManaged(true);
        message.setText("Preparing Aokuvue update…");
        detail.setText("Downloading and verifying the latest installer. Aokuvue will close after the updater starts.");
    }

    public void setFailure(Throwable error) {
        update.setDisable(false);
        later.setDisable(false);
        progress.setVisible(false);
        progress.setManaged(false);
        message.setText("Update could not be started");
        String text = error == null || error.getMessage() == null || error.getMessage().isBlank()
                ? "Please try again later."
                : error.getMessage();
        detail.setText(text);
    }
}
