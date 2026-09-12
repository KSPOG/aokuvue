package app.kspani.source;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EpisodeNormalizerTest {
    @Test
    void sortsNumericallyAndDeduplicatesWithinGroup() {
        List<SourceEpisode> input = List.of(
                ep("10", "Sub", "a"),
                ep("2", "Sub", "b"),
                ep("2", "Sub", "duplicate"),
                ep("2", "Dub", "dub"),
                ep("1", "Sub", "c")
        );

        List<SourceEpisode> out = EpisodeNormalizer.normalize(input);
        assertEquals(List.of("1:Sub", "2:Sub", "2:Dub", "10:Sub"),
                out.stream().map(e -> e.number() + ":" + e.group()).toList());
        assertEquals("b", out.get(1).episodeId());
    }

    private static SourceEpisode ep(String number, String group, String id) {
        return new SourceEpisode("test", "series", id, number, "Episode " + number, "", "", false, group, Map.of());
    }
}
