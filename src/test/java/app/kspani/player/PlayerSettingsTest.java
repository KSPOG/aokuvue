package app.kspani.player;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class PlayerSettingsTest {
    @Test
    void subtitleSizeIsRestrictedToReadablePlayerBounds() {
        assertEquals(14.0, PlayerSettings.sanitizeSubtitleSize(5.0));
        assertEquals(26.0, PlayerSettings.sanitizeSubtitleSize(26.0));
        assertEquals(48.0, PlayerSettings.sanitizeSubtitleSize(80.0));
        assertEquals(20.0, PlayerSettings.sanitizeSubtitleSize(Double.NaN));
    }
}
