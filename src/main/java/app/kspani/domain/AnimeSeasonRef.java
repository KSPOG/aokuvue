package app.kspani.domain;

import java.time.LocalDate;

/** Lightweight AniList relation used by the details-page season switcher. */
public record AnimeSeasonRef(
        int mediaId,
        String title,
        String format,
        String season,
        Integer seasonYear,
        Integer episodes,
        LocalDate startDate
) {
    public String displayLabel(int ordinal) {
        StringBuilder text = new StringBuilder("Season ").append(ordinal);
        if (title != null && !title.isBlank()) text.append(" · ").append(title);
        if (seasonYear != null) text.append(" (").append(seasonYear).append(')');
        return text.toString();
    }
}
