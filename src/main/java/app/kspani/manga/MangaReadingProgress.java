package app.kspani.manga;

import java.util.prefs.Preferences;

/** Small local bookmark store for the native manga reader. */
public final class MangaReadingProgress {
    private final Preferences preferences = Preferences.userRoot().node("app/kspani/aokuvue/manga-reader");

    public record Bookmark(String mangaId, String chapterId, int page) {}

    public Bookmark load(int mediaId) {
        String manga = preferences.get(mediaId + ".manga", "");
        String chapter = preferences.get(mediaId + ".chapter", "");
        int page = Math.max(0, preferences.getInt(mediaId + ".page", 0));
        return new Bookmark(manga, chapter, page);
    }

    public void save(int mediaId, String mangaId, String chapterId, int page) {
        preferences.put(mediaId + ".manga", mangaId == null ? "" : mangaId);
        preferences.put(mediaId + ".chapter", chapterId == null ? "" : chapterId);
        preferences.putInt(mediaId + ".page", Math.max(0, page));
    }
}
