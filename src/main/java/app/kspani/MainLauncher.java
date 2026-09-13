package app.kspani;

import javafx.application.Application;

/**
 * Native-packaging entry point for AOKVUE.
 *
 * <p>The launcher intentionally does not extend {@link Application}. This prevents the Java
 * launcher from taking its module-path-specific JavaFX startup branch before the classpath
 * JavaFX dependencies bundled by jpackage are available.</p>
 */
public final class MainLauncher {
    private MainLauncher() {
    }

    public static void main(String[] args) {
        Application.launch(Main.class, args);
    }
}
