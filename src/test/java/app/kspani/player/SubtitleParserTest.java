package app.kspani.player;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class SubtitleParserTest {
    @Test
    void parsesWebVttWithVariablePrecisionAndFormatting() {
        String source = """
                WEBVTT

                00:00:02.02 --> 00:00:05.220
                <i>Two thousand years ago.</i>
                The Mythical Age.

                00:00:06 --> 00:00:08.5 align:center
                &amp; then the next caption
                """;

        List<SubtitleCue> cues = SubtitleParser.parse(source);

        assertEquals(2, cues.size());
        assertEquals(2020, cues.get(0).startMs());
        assertEquals("Two thousand years ago.\nThe Mythical Age.", cues.get(0).text());
        assertEquals(8500, cues.get(1).endMs());
        assertEquals("& then the next caption", SubtitleParser.at(cues, 6100));
    }
}
