package app.kspani.domain;

public enum MediaListStatus {
    CURRENT,
    PLANNING,
    COMPLETED,
    DROPPED,
    PAUSED,
    REPEATING;

    public String displayName(MediaType type) {
        return switch (this) {
            case CURRENT -> type == MediaType.MANGA ? "Reading" : "Watching";
            case PLANNING -> type == MediaType.MANGA ? "Planning to Read" : "Planning to Watch";
            case COMPLETED -> "Completed";
            case DROPPED -> "Dropped";
            case PAUSED -> "Paused";
            case REPEATING -> type == MediaType.MANGA ? "Rereading" : "Rewatching";
        };
    }
}
