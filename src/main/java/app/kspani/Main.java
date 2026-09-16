package app.kspani;

import app.kspani.app.AppContext;
import app.kspani.app.AppUpdateService;
import app.kspani.app.DiagnosticLog;
import app.kspani.ui.CatalogUiEnhancer;
import app.kspani.ui.DetailsResumeEnhancer;
import app.kspani.ui.LibraryUiEnhancer;
import app.kspani.ui.MainWindow;
import app.kspani.ui.PlayerControlsEnhancer;
import app.kspani.ui.SeriesRelationsEnhancer;
import app.kspani.ui.SettingsUiEnhancer;
import app.kspani.ui.SubtitleOverlayEnhancer;
import app.kspani.ui.UpdateNotification;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import java.util.concurrent.CompletionException;

public final class Main extends Application {
    private static final long MINIMUM_SPLASH_MILLIS = 1_400;
    private AppContext context;

    private record SplashHandle(Stage stage, StackPane root, Label status, ProgressBar progress) {}

    @Override
    public void start(Stage stage) {
        DiagnosticLog.installSystemCapture();
        stage.initStyle(StageStyle.UNDECORATED);
        SplashHandle splash=createSplash();
        long splashStarted=System.nanoTime();
        splash.stage().show();
        Platform.runLater(()->initializeMainWindow(stage,splash,splashStarted));
    }

    private void initializeMainWindow(Stage stage,SplashHandle splash,long splashStarted) {
        context=AppContext.create();
        MainWindow root=new MainWindow(context);
        SettingsUiEnhancer.install(root, context.config(), getHostServices()::showDocument);
        SubtitleOverlayEnhancer.install(root, context.config());
        PlayerControlsEnhancer.install(root);
        CatalogUiEnhancer.install(root, context);
        LibraryUiEnhancer.install(root, context);
        DetailsResumeEnhancer.install(root, context);
        SeriesRelationsEnhancer.install(root, context);
        StackPane sceneRoot=new StackPane(root);
        Scene scene=new Scene(sceneRoot,1500,900,Color.web("#0A0A0F"));
        var css = Main.class.getResource("/styles/aokuvue.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());
        stage.setTitle("AOKUVUE");
        var icon = Main.class.getResource("/images/aokuvue-icon.png");
        if (icon != null) stage.getIcons().add(new Image(icon.toExternalForm()));
        stage.setMinWidth(1100);
        stage.setMinHeight(700);
        stage.setScene(scene);
        stage.setOpacity(0);
        splash.status().setText("Loading your AOKUVUE library…");

        root.initialContentReady().whenComplete((ignored,error)->Platform.runLater(()->{
            long elapsed=(System.nanoTime()-splashStarted)/1_000_000L;
            PauseTransition minimumDisplay=new PauseTransition(Duration.millis(Math.max(0,MINIMUM_SPLASH_MILLIS-elapsed)));
            minimumDisplay.setOnFinished(event->{
                splash.status().setText(error==null?"Your world is ready":"Opening AOKUVUE…");
                splash.progress().setProgress(1);
                PauseTransition settle=new PauseTransition(Duration.millis(220));
                settle.setOnFinished(done->revealMainWindow(stage,root,sceneRoot,splash));
                settle.play();
            });
            minimumDisplay.play();
        }));
    }

    private void revealMainWindow(Stage stage,MainWindow root,StackPane sceneRoot,SplashHandle splash){
        FadeTransition splashFade=new FadeTransition(Duration.millis(520),splash.root());
        splashFade.setFromValue(1);splashFade.setToValue(0);
        splashFade.setOnFinished(event->{
            splash.stage().setAlwaysOnTop(false);
            splash.stage().close();
            stage.centerOnScreen();
            stage.show();
            stage.toFront();
            Timeline windowFade=new Timeline(
                    new KeyFrame(Duration.ZERO,new KeyValue(stage.opacityProperty(),0)),
                    new KeyFrame(Duration.millis(420),new KeyValue(stage.opacityProperty(),1, Interpolator.EASE_BOTH)));
            windowFade.setOnFinished(done->{
                root.requestFocus();
                checkForUpdates(stage,sceneRoot);
            });
            windowFade.play();
        });
        splashFade.play();
    }

    private void checkForUpdates(Stage stage,StackPane sceneRoot) {
        AppUpdateService updates=new AppUpdateService(context.http().mapper());
        updates.checkForUpdate().whenComplete((available,error)->{
            if(error!=null){
                System.err.println("Aokuvue update check failed: "+rootMessage(error));
                return;
            }
            if(available.isEmpty())return;
            Platform.runLater(()->showUpdateNotification(stage,sceneRoot,updates,available.get()));
        });
    }

    private void showUpdateNotification(Stage stage,StackPane sceneRoot,AppUpdateService updates,AppUpdateService.UpdateInfo info) {
        final UpdateNotification[] holder=new UpdateNotification[1];
        Runnable dismiss=()->{
            UpdateNotification notification=holder[0];
            if(notification!=null)sceneRoot.getChildren().remove(notification);
        };
        Runnable update=()->{
            UpdateNotification notification=holder[0];
            if(notification==null)return;
            notification.setUpdating();
            updates.downloadAndLaunch(info,updates.detectInstallDirectory()).whenComplete((installer,error)->Platform.runLater(()->{
                if(error!=null){
                    notification.setFailure(errorCause(error));
                    return;
                }
                stage.close();
                Platform.exit();
            }));
        };
        UpdateNotification notification=new UpdateNotification(info,update,dismiss);
        holder[0]=notification;
        StackPane.setAlignment(notification,Pos.TOP_RIGHT);
        StackPane.setMargin(notification,new Insets(86,26,0,0));
        sceneRoot.getChildren().add(notification);
    }

    private static Throwable errorCause(Throwable error) {
        Throwable current=error;
        while(current instanceof CompletionException&&current.getCause()!=null)current=current.getCause();
        if(current.getCause()!=null&&current instanceof IllegalStateException)return current.getCause();
        return current;
    }

    private static String rootMessage(Throwable error) {
        Throwable root=errorCause(error);
        return root.getMessage()==null?root.getClass().getSimpleName():root.getMessage();
    }

    private SplashHandle createSplash(){
        ImageView art=new ImageView();var backdrop=Main.class.getResource("/images/aokuvue-moonlight.png");if(backdrop!=null)art.setImage(new Image(backdrop.toExternalForm(),760,430,false,true));art.setFitWidth(760);art.setFitHeight(430);art.setPreserveRatio(false);
        StackPane veil=new StackPane();veil.setStyle("-fx-background-color:rgba(4,3,10,.38);");
        Label wordmark=new Label("A O K U V U E");wordmark.setStyle("-fx-font-family:'Georgia';-fx-font-size:58px;-fx-text-fill:#F3EFFF;-fx-effect:dropshadow(gaussian,#8E7CFF,18,.25,0,0);");
        Label tagline=new Label("—   E n t e r   t h e   U n s e e n .   —");tagline.setStyle("-fx-font-family:'Georgia';-fx-font-size:16px;-fx-text-fill:#EAE7F5;");
        Label status=new Label("Preparing AOKUVUE…");status.setStyle("-fx-font-size:12px;-fx-text-fill:#C7C0DF;");
        ProgressBar progress=new ProgressBar();progress.setPrefWidth(260);progress.setProgress(-1);progress.setStyle("-fx-accent:#8E7CFF;");
        VBox stack=new VBox(15,wordmark,tagline,progress,status);stack.setAlignment(Pos.CENTER);stack.setPadding(new Insets(48));
        StackPane root=new StackPane(art,veil,stack);root.setStyle("-fx-background-color:#0A0A0F;-fx-border-color:#5B2A86;-fx-border-radius:10px;-fx-background-radius:10px;");
        Scene scene=new Scene(root,760,430,Color.TRANSPARENT);Stage splash=new Stage(StageStyle.TRANSPARENT);splash.setScene(scene);splash.setAlwaysOnTop(true);splash.centerOnScreen();return new SplashHandle(splash,root,status,progress);
    }

    @Override
    public void stop() {
        if (context != null) context.close();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
