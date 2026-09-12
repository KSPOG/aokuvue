package app.kspani.source;

import java.util.Locale;

/** Persistent playback-language preference used by episode, server, subtitle and video selection. */
public enum PlaybackLanguage {
    AUTO("Auto"),
    ENGLISH_SUB("English Sub"),
    ENGLISH_DUB("English Dub");

    private final String displayName;

    PlaybackLanguage(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public boolean prefersDub() {
        return this == ENGLISH_DUB;
    }

    public boolean prefersEnglishSubtitles() {
        return this == ENGLISH_SUB;
    }

    public static PlaybackLanguage parse(String value, boolean legacyPreferDub) {
        if (value != null && !value.isBlank()) {
            String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
            try { return valueOf(normalized); } catch (Exception ignored) {}
        }
        return legacyPreferDub ? ENGLISH_DUB : AUTO;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
