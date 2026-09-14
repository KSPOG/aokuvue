package app.kspani.app;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppUpdateServiceTest {
    @Test
    void extractsGradleVersion() {
        String build = "group = 'app.kspani'\nversion = '1.6.2'\n";
        assertEquals("1.6.2", AppUpdateService.extractGradleVersion(build));
    }

    @Test
    void comparesNumericVersionsRatherThanLexically() {
        assertTrue(AppUpdateService.isNewer("1.10.0", "1.9.9"));
        assertTrue(AppUpdateService.isNewer("2.0.0", "1.99.99"));
        assertFalse(AppUpdateService.isNewer("1.5.17", "1.5.17"));
        assertFalse(AppUpdateService.isNewer("1.5.16", "1.5.17"));
    }

    @Test
    void acceptsLeadingVersionPrefix() {
        assertTrue(AppUpdateService.compareVersions("v1.1.0", "1.0.9") > 0);
    }
}
