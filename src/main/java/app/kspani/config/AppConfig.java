package app.kspani.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class AppConfig {
    private final Path dataDirectory;
    private final Path file;
    private final Properties properties = new Properties();

    private AppConfig(Path dataDirectory) {
        this.dataDirectory = dataDirectory;
        this.file = dataDirectory.resolve("app.properties");
    }

    public static AppConfig load() {
        String appData = System.getenv("APPDATA");
        Path dir = appData != null && !appData.isBlank()
                ? Path.of(appData, "KSPAni", "clean-v1")
                : Path.of(System.getProperty("user.home"), ".kspani", "clean-v1");
        AppConfig config = new AppConfig(dir);
        config.read();
        config.applyEnvironment();
        return config;
    }

    private void read() {
        try {
            Files.createDirectories(dataDirectory);
            if (Files.exists(file)) {
                try (InputStream in = Files.newInputStream(file)) {
                    properties.load(in);
                }
            }
            defaults();
            save();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to load Aokuvue settings", e);
        }
    }

    private void defaults() {
        putIfMissing("anilist.accessToken", "");
        removeRetiredSourceSettings();
        putIfMissing("content.includeAdult", "true");
        putIfMissing("player.watchPercentage", "0.85");
        putIfMissing("player.autoMarkWatched", "true");
        putIfMissing("player.autoPlay", "true");
        putIfMissing("player.seekSeconds", "10");
        putIfMissing("player.autoSkipIntro", "true");
        putIfMissing("player.autoSkipOutro", "true");
        putIfMissing("player.autoNextEpisode", "true");
        putIfMissing("player.defaultSpeed", "1.0");
        putIfMissing("player.subtitleSize", "20");
        putIfMissing("ui.startTab", "HOME");
        putIfMissing("ui.compactCards", "false");
        properties.setProperty("runtime.schemaVersion", "2");
    }

    private void applyEnvironment() {
        env("anilist.accessToken", "KSPANI_ANILIST_ACCESS_TOKEN");
    }

    private void removeRetiredSourceSettings() {
        String[] retired = {
                "source.defaultAnime", "source.preferDub", "source.languagePreference",
                "local.libraryPaths",
                "jellyfin.url", "jellyfin.apiKey", "jellyfin.userId",
                "emby.url", "emby.apiKey", "emby.userId",
                "plex.url", "plex.token", "plex.clientId",
                "authorized.baseUrl", "authorized.apiKey"
        };
        for (String key : retired) properties.remove(key);
    }

    private void env(String key, String envName) {
        String value = System.getenv(envName);
        if (value != null && !value.isBlank()) {
            properties.setProperty(key, value.trim());
        }
    }

    private void putIfMissing(String key, String value) {
        if (!properties.containsKey(key)) {
            properties.setProperty(key, value);
        }
    }

    public synchronized void save() {
        try {
            Files.createDirectories(dataDirectory);
            try (OutputStream out = Files.newOutputStream(file)) {
                properties.store(out, "Aokuvue settings");
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to save Aokuvue settings", e);
        }
    }

    public synchronized String get(String key) {
        return properties.getProperty(key, "").trim();
    }

    public synchronized String get(String key, String fallback) {
        String value = get(key);
        return value.isBlank() ? fallback : value;
    }

    public synchronized boolean getBoolean(String key, boolean fallback) {
        String value = get(key);
        return value.isBlank() ? fallback : Boolean.parseBoolean(value);
    }

    public synchronized int getInt(String key, int fallback) {
        try { return Integer.parseInt(get(key)); } catch (Exception ignored) { return fallback; }
    }

    public synchronized double getDouble(String key, double fallback) {
        try { return Double.parseDouble(get(key)); } catch (Exception ignored) { return fallback; }
    }

    public synchronized void set(String key, String value) {
        properties.setProperty(key, value == null ? "" : value.trim());
        save();
    }

    public Path dataDirectory() { return dataDirectory; }
    public Path settingsFile() { return file; }
}
