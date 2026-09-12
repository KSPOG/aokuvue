package app.kspani.source;

import app.kspani.data.AppDatabase;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class SourceStateRepository {
    private final AppDatabase db;
    private final ObjectMapper mapper;

    public SourceStateRepository(AppDatabase db, ObjectMapper mapper) {
        this.db = db;
        this.mapper = mapper;
    }

    public Optional<SourceSelection> loadSelection(int mediaId) {
        String sql = "SELECT * FROM source_selection WHERE media_id=?";
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            ps.setInt(1, mediaId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                Integer subtitle = rs.getObject("subtitle_index") == null ? null : rs.getInt("subtitle_index");
                boolean legacyDub = rs.getInt("prefer_dub") != 0;
                PlaybackLanguage language = PlaybackLanguage.parse(rs.getString("language_preference"), legacyDub);
                return Optional.of(new SourceSelection(
                        mediaId,
                        rs.getString("source_id"),
                        rs.getString("server_name"),
                        legacyDub,
                        rs.getInt("video_index"),
                        subtitle,
                        rs.getString("selected_episode"),
                        language
                ));
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load source selection", e);
        }
    }

    public void saveSelection(SourceSelection selection) {
        String sql = """
                INSERT INTO source_selection(media_id,source_id,server_name,prefer_dub,language_preference,video_index,subtitle_index,selected_episode,updated_at)
                VALUES(?,?,?,?,?,?,?,?,?)
                ON CONFLICT(media_id) DO UPDATE SET
                  source_id=excluded.source_id,
                  server_name=excluded.server_name,
                  prefer_dub=excluded.prefer_dub,
                  language_preference=excluded.language_preference,
                  video_index=excluded.video_index,
                  subtitle_index=excluded.subtitle_index,
                  selected_episode=excluded.selected_episode,
                  updated_at=excluded.updated_at
                """;
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            ps.setInt(1, selection.mediaId());
            ps.setString(2, selection.sourceId());
            ps.setString(3, selection.serverName());
            ps.setInt(4, selection.preferDub() ? 1 : 0);
            ps.setString(5, selection.languagePreference().name());
            ps.setInt(6, selection.videoIndex());
            if (selection.subtitleIndex() == null) ps.setObject(7, null); else ps.setInt(7, selection.subtitleIndex());
            ps.setString(8, selection.selectedEpisode());
            ps.setLong(9, Instant.now().getEpochSecond());
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to save source selection", e);
        }
    }

    public Optional<SourceSeries> loadSeriesMatch(int mediaId, String sourceId) {
        String sql = "SELECT * FROM source_series_match WHERE media_id=? AND source_id=?";
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            ps.setInt(1, mediaId);
            ps.setString(2, sourceId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                List<String> names = mapper.readValue(rs.getString("other_names_json"), new TypeReference<List<String>>(){});
                Map<String,String> extra = mapper.readValue(rs.getString("extra_json"), new TypeReference<Map<String,String>>(){});
                Integer total = rs.getObject("total") == null ? null : rs.getInt("total");
                return Optional.of(new SourceSeries(
                        sourceId,
                        rs.getString("provider_series_id"),
                        rs.getString("name"),
                        rs.getString("link"),
                        rs.getString("cover_url"),
                        names,
                        total,
                        extra
                ));
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load source series match", e);
        }
    }

    public void saveSeriesMatch(int mediaId, SourceSeries series) {
        String sql = """
                INSERT INTO source_series_match(media_id,source_id,provider_series_id,name,link,cover_url,other_names_json,total,extra_json,updated_at)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(media_id,source_id) DO UPDATE SET
                  provider_series_id=excluded.provider_series_id,
                  name=excluded.name,
                  link=excluded.link,
                  cover_url=excluded.cover_url,
                  other_names_json=excluded.other_names_json,
                  total=excluded.total,
                  extra_json=excluded.extra_json,
                  updated_at=excluded.updated_at
                """;
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            ps.setInt(1, mediaId);
            ps.setString(2, series.providerId());
            ps.setString(3, series.seriesId());
            ps.setString(4, series.name());
            ps.setString(5, series.link() == null ? "" : series.link());
            ps.setString(6, series.coverUrl() == null ? "" : series.coverUrl());
            ps.setString(7, mapper.writeValueAsString(series.otherNames()));
            if (series.total() == null) ps.setObject(8, null); else ps.setInt(8, series.total());
            ps.setString(9, mapper.writeValueAsString(series.extra()));
            ps.setLong(10, Instant.now().getEpochSecond());
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to save source series match", e);
        }
    }

    public void clearSeriesMatch(int mediaId, String sourceId) {
        try (PreparedStatement ps = db.connection().prepareStatement("DELETE FROM source_series_match WHERE media_id=? AND source_id=?")) {
            ps.setInt(1, mediaId);
            ps.setString(2, sourceId);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to clear source match", e);
        }
    }
}
