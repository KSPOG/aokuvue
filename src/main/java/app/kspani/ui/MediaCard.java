package app.kspani.ui;

import app.kspani.domain.AniMedia;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;

final class MediaCard extends VBox {
    private static final double CARD_WIDTH = 190;
    private static final double ART_HEIGHT = 252;

    MediaCard(AniMedia media, Consumer<AniMedia> onOpen) {
        this(media, onOpen, null);
    }

    MediaCard(AniMedia media, Consumer<AniMedia> onOpen, String metaOverride) {
        setSpacing(0);
        setPrefWidth(CARD_WIDTH);
        setMaxWidth(CARD_WIDTH);
        getStyleClass().add("media-card");

        ImageView cover = new ImageView();
        cover.setFitWidth(CARD_WIDTH);
        cover.setFitHeight(ART_HEIGHT);
        cover.setPreserveRatio(false);
        cover.setSmooth(true);
        if (media.coverImage() != null && !media.coverImage().isBlank()) {
            cover.setImage(new Image(media.coverImage(), CARD_WIDTH, ART_HEIGHT, true, true, true));
        }
        StackPane art = new StackPane(cover);
        art.setPrefSize(CARD_WIDTH, ART_HEIGHT);
        art.setMinSize(CARD_WIDTH, ART_HEIGHT);
        art.getStyleClass().add("poster-art");

        StackPane shade = new StackPane();
        shade.getStyleClass().add("poster-scrim");
        shade.setMouseTransparent(true);
        art.getChildren().add(shade);

        if (media.averageScore() != null && media.averageScore() > 0) {
            Label score = new Label("★ " + media.averageScore() + "%");
            score.getStyleClass().add("score-badge");
            StackPane.setAlignment(score, Pos.TOP_RIGHT);
            art.getChildren().add(score);
        }

        Label title = new Label(media.title());
        title.setWrapText(true);
        title.getStyleClass().add("poster-title");

        String meta = metaOverride != null && !metaOverride.isBlank()
                ? metaOverride
                : media.format() == null || media.format().isBlank() ? media.type().name() : media.format().replace('_', ' ');
        Label info = new Label(meta);
        info.getStyleClass().add("poster-meta");

        Label nativeTitle=new Label(media.nativeTitle()==null?"":media.nativeTitle());nativeTitle.getStyleClass().add("poster-native");
        FlowPane genres=new FlowPane(5,4);media.genres().stream().limit(2).forEach(g->{Label chip=new Label(g);chip.getStyleClass().add("poster-chip");genres.getChildren().add(chip);});
        VBox copy = new VBox(3, title, nativeTitle, genres, info);
        copy.setPrefHeight(86);copy.getStyleClass().add("poster-copy");
        Label favorite = new Label("♡");favorite.getStyleClass().add("poster-favorite");StackPane.setAlignment(favorite,Pos.TOP_RIGHT);StackPane.setMargin(favorite,new javafx.geometry.Insets(8,8,0,0));art.getChildren().add(favorite);
        getChildren().addAll(art,copy);
        setOnMouseClicked(event -> onOpen.accept(media));
    }
}
