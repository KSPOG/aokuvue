package app.kspani.player;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbeddedPlayerStatusTest {
    @Test
    void detectsMissingProviderFile() {
        assertTrue(EmbeddedPlayerStatus.unavailable("""
                We can't find the file you are looking for.
                It may have been deleted by the owner.
                Error Code: 410
                """));
    }

    @Test
    void doesNotRejectNormalPlayerText() {
        assertFalse(EmbeddedPlayerStatus.unavailable("Play Pause 04:02 / 23:40 Quality 1080p"));
        assertFalse(EmbeddedPlayerStatus.unavailable(null));
    }
}
