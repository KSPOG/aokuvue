package app.kspani.source;

import app.kspani.domain.AniMedia;
import app.kspani.domain.MediaType;
import app.kspani.domain.UserListEntry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TitleMatcherTest {
    @Test
    void englishAndRomajiAliasesBeatUnrelatedSeries() {
        AniMedia media = media("Demon Slayer: Kimetsu no Yaiba", "Kimetsu no Yaiba", 26, 0);
        SourceSeries unrelated = series("Daemons of the Shadow Realm", 24);
        SourceSeries correct = new SourceSeries("test", "demon-slayer", "Kimetsu no Yaiba", "", "",
                List.of("Demon Slayer", "Demon Slayer: Kimetsu no Yaiba"), 26, Map.of());

        List<SourceSeries> ranked = TitleMatcher.ranked(media, List.of(unrelated, correct));
        assertEquals("demon-slayer", ranked.get(0).seriesId());
        assertTrue(TitleMatcher.score(media, correct) > TitleMatcher.score(media, unrelated));
    }

    @Test
    void episodeTotalAndProgressHelpRejectWrongCandidate() {
        AniMedia media = media("One Piece", "One Piece", null, 793);
        SourceSeries tooShort = series("One Piece Special", 12);
        SourceSeries longSeries = series("ONE PIECE", 1100);

        assertTrue(TitleMatcher.score(media, longSeries) > TitleMatcher.score(media, tooShort));
    }

    @Test
    void explicitSeasonNumberPreventsCrossSeasonAutoMatch() {
        AniMedia media = media(
                "Mushoku Tensei: Jobless Reincarnation Season 3",
                "Mushoku Tensei III: Isekai Ittara Honki Dasu",
                14, 0);
        SourceSeries oldSeason = new SourceSeries(
                "test", "mushoku-tensei-ii-isekai-ittara-honki-dasu-part-2",
                "Mushoku Tensei II: Isekai Ittara Honki Dasu Part 2", "", "",
                List.of("Mushoku Tensei: Jobless Reincarnation Season 2 Part 2"), 12, Map.of());
        SourceSeries correctSeason = new SourceSeries(
                "test", "mushoku-tensei-iii-isekai-ittara-honki-dasu",
                "Mushoku Tensei III: Isekai Ittara Honki Dasu", "", "",
                List.of("Mushoku Tensei: Jobless Reincarnation Season 3"), 14, Map.of());

        assertTrue(TitleMatcher.score(media, oldSeason) < 0.55);
        assertTrue(TitleMatcher.score(media, correctSeason) > TitleMatcher.score(media, oldSeason));
    }

    private static SourceSeries series(String name, Integer total) {
        return new SourceSeries("test", name.toLowerCase().replace(' ', '-'), name, "", "", List.of(), total, Map.of());
    }

    private static AniMedia media(String english, String romaji, Integer episodes, int progress) {
        return new AniMedia(
                1, MediaType.ANIME, english, english, romaji, "", List.of(), "", "", "",
                "TV", "FINISHED", "", null, episodes, null, 24, 80, 80, 1, 1, 1,
                List.of(), List.of(), List.of(), "MANGA", null, null, null, null, "", null,
                List.of(), List.of(), new UserListEntry(null, null, progress, 0, 0, 0, false, "", List.of(), null, null)
        );
    }
}
