package app.kspani.source;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class EpisodeLanguageSelectorTest {
    @Test
    void englishDubFiltersDubVariantsWhenProviderLabelsThem() {
        List<SourceEpisode> input = List.of(
                ep("1", "English Sub", "sub-1"),
                ep("1", "English Dub", "dub-1"),
                ep("2", "English Sub", "sub-2"),
                ep("2", "English Dub", "dub-2")
        );

        List<SourceEpisode> out = EpisodeLanguageSelector.filterEpisodes(input, PlaybackLanguage.ENGLISH_DUB);
        assertEquals(List.of("dub-1", "dub-2"), out.stream().map(SourceEpisode::episodeId).toList());
    }

    @Test
    void englishSubChoosesEnglishSubtitleTrack() {
        List<SubtitleTrack> tracks = List.of(
                new SubtitleTrack("Spanish", URI.create("https://example.test/es.vtt"), true, Map.of()),
                new SubtitleTrack("English", URI.create("https://example.test/en.vtt"), false, Map.of())
        );
        assertEquals(1, EpisodeLanguageSelector.englishSubtitleIndex(tracks, null));
    }

    @Test
    void autoChoosesProviderDefaultSubtitleTrack() {
        List<SubtitleTrack> tracks = List.of(
                new SubtitleTrack("English", URI.create("https://example.test/en.vtt"), true, Map.of()),
                new SubtitleTrack("Spanish", URI.create("https://example.test/es.vtt"), false, Map.of())
        );
        assertEquals(0, EpisodeLanguageSelector.defaultSubtitleIndex(tracks, null));
    }

    @Test
    void dualLanguageEpisodesRemainVisibleForSubPreference() {
        List<SourceEpisode> input = List.of(
                ep("1", "SUB · DUB", "dual-1"),
                ep("2", "SUB", "sub-2"),
                ep("3", "DUB", "dub-3")
        );

        List<SourceEpisode> out = EpisodeLanguageSelector.filterEpisodes(input, PlaybackLanguage.ENGLISH_SUB);
        assertEquals(List.of("dual-1", "sub-2"), out.stream().map(SourceEpisode::episodeId).toList());
    }

    @Test
    void englishDubDoesNotRequireSubtitleTrack() {
        SourceSelection selection = SourceSelection.defaults(1, "test", PlaybackLanguage.ENGLISH_DUB);
        assertNull(selection.subtitleIndex());
        assertEquals(PlaybackLanguage.ENGLISH_DUB, selection.languagePreference());
    }

    private static SourceEpisode ep(String number, String group, String id) {
        return new SourceEpisode("test", "series", id, number, "Episode " + number, "", "", false, group, Map.of());
    }
}
