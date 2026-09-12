package app.kspani.player;

import app.kspani.data.AppDatabase;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class PlaybackProgressRepository {
    public record Progress(long positionMs, long durationMs, boolean watched) {}
    public record ContinueEntry(
            int mediaId,
            String sourceId,
            String episodeNumber,
            long positionMs,
            long durationMs,
            boolean watched,
            long updatedAt
    ) {}

    private final AppDatabase db;

    public PlaybackProgressRepository(AppDatabase db) { this.db = db; }

    public Progress load(int mediaId, String sourceId, String episodeNumber) {
        String sql = "SELECT position_ms,duration_ms,watched FROM playback_progress WHERE media_id=? AND source_id=? AND episode_number=?";
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            ps.setInt(1, mediaId); ps.setString(2, sourceId); ps.setString(3, episodeNumber);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return new Progress(0, 0, false);
                return new Progress(rs.getLong(1), rs.getLong(2), rs.getInt(3) != 0);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load playback progress", e);
        }
    }

    public void save(int mediaId, String sourceId, String episodeNumber, long positionMs, long durationMs, boolean watched) {
        String sql = """
                INSERT INTO playback_progress(media_id,source_id,episode_number,position_ms,duration_ms,watched,updated_at)
                VALUES(?,?,?,?,?,?,?)
                ON CONFLICT(media_id,source_id,episode_number) DO UPDATE SET
                  position_ms=excluded.position_ms,
                  duration_ms=excluded.duration_ms,
                  watched=MAX(playback_progress.watched,excluded.watched),
                  updated_at=excluded.updated_at
                """;
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            ps.setInt(1, mediaId); ps.setString(2, sourceId); ps.setString(3, episodeNumber);
            ps.setLong(4, Math.max(0, positionMs)); ps.setLong(5, Math.max(0, durationMs)); ps.setInt(6, watched ? 1 : 0);
            ps.setLong(7, Instant.now().getEpochSecond()); ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to save playback progress", e);
        }
    }

    /** Records the selected episode immediately, including opaque embedded players with no clock API. */
    public void touch(int mediaId, String sourceId, String episodeNumber) {
        Progress existing = load(mediaId, sourceId, episodeNumber);
        save(mediaId, sourceId, episodeNumber, existing.positionMs(), existing.durationMs(), existing.watched());
    }

    public void markWatched(int mediaId, String sourceId, String episodeNumber) {
        Progress existing = load(mediaId, sourceId, episodeNumber);
        save(mediaId, sourceId, episodeNumber, existing.durationMs(), existing.durationMs(), true);
    }

    /** Returns only the latest selected/played episode for each series. */
    public List<ContinueEntry> recent(int limit) {
        String sql = """
                SELECT p.media_id,p.source_id,p.episode_number,p.position_ms,p.duration_ms,p.watched,p.updated_at
                FROM playback_progress p
                WHERE p.updated_at=(
                  SELECT MAX(latest.updated_at) FROM playback_progress latest WHERE latest.media_id=p.media_id
                )
                ORDER BY p.updated_at DESC
                LIMIT ?
                """;
        List<ContinueEntry> result = new ArrayList<>();
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            ps.setInt(1, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new ContinueEntry(
                            rs.getInt(1), rs.getString(2), rs.getString(3),
                            rs.getLong(4), rs.getLong(5), rs.getInt(6) != 0, rs.getLong(7)));
                }
            }
            return List.copyOf(result);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load continue-watching entries", e);
        }
    }
}
