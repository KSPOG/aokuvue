package app.kspani.app;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class AppVersionTest {
    @Test void displaysCurrentBetaChannelVersion() {
        assertEquals("Beta - 0.0.5", AppVersion.display());
        assertEquals("Beta", AppVersion.channel());
        assertEquals("0.0.5", AppVersion.channelVersion());
    }
}
