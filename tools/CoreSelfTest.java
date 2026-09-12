import app.kspani.domain.AniMedia;
import app.kspani.domain.MediaType;
import app.kspani.domain.UserListEntry;
import app.kspani.source.EpisodeNormalizer;
import app.kspani.source.EpisodeLanguageSelector;
import app.kspani.source.PlaybackLanguage;
import app.kspani.source.ProviderPageIdentity;
import app.kspani.anilist.SeasonRelationMatcher;
import app.kspani.player.SubtitleParser;
import app.kspani.source.SourceEpisode;
import app.kspani.source.SourceSeries;
import app.kspani.source.TitleMatcher;

import java.util.List;
import java.util.Map;

public final class CoreSelfTest {
    public static void main(String[] args) {
        AniMedia demon = media("Demon Slayer: Kimetsu no Yaiba", "Kimetsu no Yaiba", 26, 0);
        SourceSeries wrong = series("Daemons of the Shadow Realm", 24);
        SourceSeries correct = new SourceSeries("test", "kimetsu", "Kimetsu no Yaiba", "", "",
                List.of("Demon Slayer: Kimetsu no Yaiba"), 26, Map.of());
        require(TitleMatcher.ranked(demon, List.of(wrong, correct)).get(0).seriesId().equals("kimetsu"),
                "Demon Slayer source match did not rank correctly");

        AniMedia onePiece = media("One Piece", "One Piece", null, 793);
        require(TitleMatcher.score(onePiece, series("ONE PIECE", 1100))
                        > TitleMatcher.score(onePiece, series("One Piece Special", 12)),
                "Long-running title/progress scoring regression");

        AniMedia mushokuS3 = media(
                "Mushoku Tensei: Jobless Reincarnation Season 3",
                "Mushoku Tensei III: Isekai Ittara Honki Dasu", 14, 0);
        SourceSeries mushokuS2p2 = new SourceSeries(
                "gogoanime", "mushoku-tensei-ii-isekai-ittara-honki-dasu-part-2",
                "Mushoku Tensei II: Isekai Ittara Honki Dasu Part 2", "", "",
                List.of("Mushoku Tensei: Jobless Reincarnation Season 2 Part 2"), 12, Map.of());
        require(TitleMatcher.score(mushokuS3, mushokuS2p2) < 0.55,
                "Season-aware matching failed to reject a Season 2 candidate for Season 3");
        require(ProviderPageIdentity.matches(mushokuS3,
                        "Mushoku Tensei III: Isekai Ittara Honki Dasu Episode 1 English Subbed\n"
                                + "Mushoku Tensei III: Isekai Ittara Honki Dasu"),
                "Correct Gogo provider page identity was rejected");
        require(!ProviderPageIdentity.matches(mushokuS3,
                        "Grand Blue Season 3 Episode 1 English Subbed\nGrand Blue Season 3"),
                "Wrong-series Gogo provider page identity was accepted");

        require(ProviderPageIdentity.matchesGogoSeriesPage(
                        mushokuS3,
                        "https://gogoanime.by/series/mushoku-tensei-iii-isekai-ittara-honki-dasu/",
                        "Mushoku Tensei III: Isekai Ittara Honki Dasu - Gogoanime\n/series/mushoku-tensei-iii-isekai-ittara-honki-dasu/"),
                "Strict Gogo series page validation rejected the correct series slug");
        require(!ProviderPageIdentity.matchesGogoEpisodePage(
                        mushokuS3,
                        "mushoku-tensei-iii-isekai-ittara-honki-dasu",
                        "https://gogoanime.by/grand-blue-season-3-episode-1-english-subbed/",
                        "Grand Blue Season 3 Episode 1 English Subbed - Gogoanime",
                        "1"),
                "Episode page escaped the verified Gogo series slug");

        String wrongSearch = java.net.URLEncoder.encode(
                "https://gogoanime.by/series/grand-blue-season-3/", java.nio.charset.StandardCharsets.UTF_8)
                + "\t"
                + java.net.URLEncoder.encode("Grand Blue Season 3", java.nio.charset.StandardCharsets.UTF_8);
        String correctSearch = java.net.URLEncoder.encode(
                "https://gogoanime.by/series/mushoku-tensei-iii-isekai-ittara-honki-dasu/",
                java.nio.charset.StandardCharsets.UTF_8)
                + "\t"
                + java.net.URLEncoder.encode(
                        "Mushoku Tensei III: Isekai Ittara Honki Dasu",
                        java.nio.charset.StandardCharsets.UTF_8);
        String baseSeasonSearch = java.net.URLEncoder.encode(
                "https://gogoanime.by/series/mushoku-tensei-isekai-ittara-honki-dasu/",
                java.nio.charset.StandardCharsets.UTF_8)
                + "\t"
                + java.net.URLEncoder.encode(
                        "Mushoku Tensei: Isekai Ittara Honki Dasu",
                        java.nio.charset.StandardCharsets.UTF_8);
        require(ProviderPageIdentity.bestGogoSeriesUrl(mushokuS3, wrongSearch).isBlank(),
                "Provider search accepted unrelated Season 3 result");
        require(ProviderPageIdentity.bestGogoSeriesUrl(mushokuS3, baseSeasonSearch).isBlank(),
                "Provider search accepted unnumbered base series for explicit Season 3");
        require(ProviderPageIdentity.bestGogoSeriesUrl(mushokuS3, wrongSearch + "\n" + correctSearch)
                        .contains("mushoku-tensei-iii"),
                "Provider search failed to select the verified Season 3 result");

        List<SourceEpisode> normalized = EpisodeNormalizer.normalize(List.of(
                ep("10", "Sub", "10"), ep("2", "Sub", "2"), ep("2", "Sub", "dup"),
                ep("2", "Dub", "2dub"), ep("1", "Sub", "1")
        ));
        require(normalized.size() == 4, "Episode deduplication failed");
        require(normalized.get(0).number().equals("1") && normalized.get(3).number().equals("10"),
                "Episode numeric sorting failed");
        require(normalized.get(1).episodeId().equals("2"), "First source episode was not preserved during dedupe");

        List<SourceEpisode> languageEpisodes = EpisodeLanguageSelector.filterEpisodes(List.of(
                ep("1", "English Sub", "sub1"), ep("1", "English Dub", "dub1"),
                ep("2", "English Sub", "sub2"), ep("2", "English Dub", "dub2")
        ), PlaybackLanguage.ENGLISH_DUB);
        require(languageEpisodes.stream().map(SourceEpisode::episodeId).toList().equals(List.of("dub1","dub2")),
                "English Dub episode selection failed");

        AniMedia attack = media("Attack on Titan Season 2", "Shingeki no Kyojin Season 2", 12, 0);
        require(SeasonRelationMatcher.sameFamily(attack, List.of("Attack on Titan", "Shingeki no Kyojin")),
                "Season-family matcher failed to connect a prequel season");
        require(!SeasonRelationMatcher.sameFamily(attack, List.of("Boruto: Naruto Next Generations")),
                "Season-family matcher accepted an unrelated sequel franchise");
        AniMedia naruto = media("Naruto", "Naruto", 220, 0);
        require(!SeasonRelationMatcher.sameFamily(naruto, List.of("Naruto: Shippuden")),
                "Season-family matcher incorrectly treated a sequel series as a numbered season");

        require(SubtitleParser.parse("WEBVTT\n\n00:00:01.000 --> 00:00:02.500\nHello\n").size()==1,
                "WebVTT subtitle parsing failed");

        System.out.println("KSP Ani clean core self-test: PASS");
    }

    private static SourceEpisode ep(String number, String group, String id) {
        return new SourceEpisode("test", "series", id, number, "Episode " + number, "", "", false, group, Map.of());
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

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
