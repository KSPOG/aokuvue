package app.kspani.anilist;

import app.kspani.config.AppConfig;

import java.awt.Desktop;
import java.net.URI;

public final class AniListAuthService {
    public static final String CLIENT_ID = "48788";
    public static final String AUTH_PIN_REDIRECT = "https://anilist.co/api/v2/oauth/pin";

    private final AppConfig config;

    public AniListAuthService(AppConfig config) {
        this.config = config;
    }

    public URI authorizationUri() {
        return URI.create("https://anilist.co/api/v2/oauth/authorize?client_id=" + CLIENT_ID + "&response_type=token");
    }

    public void openBrowser() {
        try {
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                throw new IllegalStateException("System browser integration is unavailable.");
            }
            Desktop.getDesktop().browse(authorizationUri());
        } catch (Exception e) {
            throw new IllegalStateException("Unable to open AniList login in the default browser.", e);
        }
    }

    public void saveToken(String token) {
        if (token == null || token.isBlank()) throw new IllegalArgumentException("AniList token cannot be empty.");
        config.set("anilist.accessToken", token.trim());
    }

    public void clearToken() { config.set("anilist.accessToken", ""); }
    public boolean loggedIn() { return !config.get("anilist.accessToken").isBlank(); }
}
