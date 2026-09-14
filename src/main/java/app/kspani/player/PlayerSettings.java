package app.kspani.player;

import app.kspani.config.AppConfig;

public record PlayerSettings(
        double watchPercentage,
        boolean autoMarkWatched,
        boolean autoPlay,
        int seekSeconds,
        double defaultSpeed,
        double subtitleSize
) {
    public static PlayerSettings load(AppConfig config) {
        double pct = Math.max(0.5, Math.min(1.0, config.getDouble("player.watchPercentage", 0.85)));
        return new PlayerSettings(
                pct,
                config.getBoolean("player.autoMarkWatched", true),
                config.getBoolean("player.autoPlay", true),
                Math.max(5, config.getInt("player.seekSeconds", 10)),
                Math.max(0.25, Math.min(4.0, config.getDouble("player.defaultSpeed", 1.0))),
                sanitizeSubtitleSize(config.getDouble("player.subtitleSize", 20.0))
        );
    }

    public static double sanitizeSubtitleSize(double size) {
        if (!Double.isFinite(size)) return 20.0;
        return Math.max(14.0, Math.min(48.0, size));
    }
}
