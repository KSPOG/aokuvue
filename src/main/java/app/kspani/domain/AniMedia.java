package app.kspani.domain;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;

public record AniMedia(
        int id,
        MediaType type,
        String title,
        String englishTitle,
        String romajiTitle,
        String nativeTitle,
        List<String> synonyms,
        String coverImage,
        String bannerImage,
        String description,
        String format,
        String releaseStatus,
        String season,
        Integer seasonYear,
        Integer totalEpisodes,
        Integer totalChapters,
        Integer durationMinutes,
        Integer averageScore,
        Integer meanScore,
        Integer popularity,
        Integer favourites,
        Integer trending,
        List<String> genres,
        List<String> tags,
        List<String> studios,
        String sourceMaterial,
        LocalDate startDate,
        LocalDate endDate,
        Integer nextAiringEpisode,
        Long nextAiringAtEpochSeconds,
        String trailerUrl,
        URI siteUri,
        List<StreamingServiceLink> streamingServices,
        List<StreamingEpisodeLink> streamingEpisodes,
        UserListEntry listEntry
) {
    public AniMedia {
        synonyms = synonyms == null ? List.of() : List.copyOf(synonyms);
        genres = genres == null ? List.of() : List.copyOf(genres);
        tags = tags == null ? List.of() : List.copyOf(tags);
        studios = studios == null ? List.of() : List.copyOf(studios);
        streamingServices = streamingServices == null ? List.of() : List.copyOf(streamingServices);
        streamingEpisodes = streamingEpisodes == null ? List.of() : List.copyOf(streamingEpisodes);
        listEntry = listEntry == null ? UserListEntry.empty() : listEntry;
    }

    public List<String> searchTitles() {
        java.util.LinkedHashSet<String> titles = new java.util.LinkedHashSet<>();
        add(titles, title);
        add(titles, englishTitle);
        add(titles, romajiTitle);
        add(titles, nativeTitle);
        synonyms.forEach(value -> add(titles, value));
        return List.copyOf(titles);
    }

    private static void add(java.util.Set<String> target, String value) {
        if (value != null && !value.isBlank()) {
            target.add(value.trim());
        }
    }

    public int knownProgress() {
        return listEntry == null ? 0 : listEntry.progress();
    }
}
