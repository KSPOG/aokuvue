package app.kspani.player;

import app.kspani.data.AppDatabase;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PlaybackProgressRepositoryTest {
    @Test
    void continueWatchingKeepsLatestEpisodePerSeries() throws Exception {
        var directory = Files.createTempDirectory("kspani-progress-");
        try (var database = new AppDatabase(directory.resolve("test.db"))) {
            database.initialize();
            var repository = new PlaybackProgressRepository(database);
            repository.save(20, "anikoto", "1", 15_000, 1_200_000, false);
            Thread.sleep(1_100);
            repository.touch(20, "anikoto", "2");

            var recent = repository.recent(10);
            assertEquals(1, recent.size());
            assertEquals("2", recent.get(0).episodeNumber());
            repository.markWatched(20, "anikoto", "2");
            assertTrue(repository.recent(10).get(0).watched());
        } finally {
            try (var files = Files.walk(directory)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) {}
                });
            }
        }
    }
}
