package app.kspani.anilist;

import app.kspani.domain.AniMedia;
import app.kspani.domain.MediaListStatus;
import app.kspani.domain.UserListEntry;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class AniListListService {
    private final AniListClient client;

    public AniListListService(AniListClient client) {
        this.client = client;
    }

    public CompletableFuture<UserListEntry> save(
            AniMedia media,
            MediaListStatus status,
            int progress,
            double score,
            int repeat,
            int priority,
            String notes,
            boolean privateEntry,
            List<String> customLists,
            LocalDate startedAt,
            LocalDate completedAt
    ) {
        String mutation = """
                mutation($mediaId:Int!, $status:MediaListStatus, $progress:Int, $score:Float, $repeat:Int,
                         $priority:Int, $notes:String, $private:Boolean, $customLists:[String],
                         $startedAt:FuzzyDateInput, $completedAt:FuzzyDateInput) {
                  SaveMediaListEntry(mediaId:$mediaId, status:$status, progress:$progress, score:$score,
                    repeat:$repeat, priority:$priority, notes:$notes, private:$private, customLists:$customLists,
                    startedAt:$startedAt, completedAt:$completedAt) {
                    id status score progress repeat priority private notes customLists
                    startedAt { year month day }
                    completedAt { year month day }
                  }
                }
                """;
        Map<String,Object> vars = new LinkedHashMap<>();
        vars.put("mediaId", media.id());
        vars.put("status", status == null ? null : status.name());
        vars.put("progress", Math.max(0, progress));
        vars.put("score", score);
        vars.put("repeat", Math.max(0, repeat));
        vars.put("priority", Math.max(0, priority));
        vars.put("notes", notes == null ? "" : notes);
        vars.put("private", privateEntry);
        vars.put("customLists", customLists == null ? List.of() : customLists);
        vars.put("startedAt", dateInput(startedAt));
        vars.put("completedAt", dateInput(completedAt));
        return client.execute(mutation, vars).thenApply(root -> parse(root.path("data").path("SaveMediaListEntry")));
    }

    public CompletableFuture<UserListEntry> setProgress(AniMedia media, int progress) {
        UserListEntry current = media.listEntry();
        int safe = Math.max(current.progress(), progress);
        MediaListStatus status = current.status();
        if (status == null || status == MediaListStatus.PLANNING || status == MediaListStatus.PAUSED || status == MediaListStatus.DROPPED) {
            status = MediaListStatus.CURRENT;
        }
        Integer total = media.type() == app.kspani.domain.MediaType.MANGA ? media.totalChapters() : media.totalEpisodes();
        if (total != null && total > 0 && safe >= total) {
            safe = total;
            status = MediaListStatus.COMPLETED;
        } else if (current.status() == MediaListStatus.REPEATING) {
            status = MediaListStatus.REPEATING;
        }
        return save(media, status, safe, current.score(), current.repeat(), current.priority(), current.notes(),
                current.privateEntry(), current.customLists(), current.startedAt(),
                status == MediaListStatus.COMPLETED && current.completedAt() == null ? LocalDate.now() : current.completedAt());
    }

    public CompletableFuture<Boolean> delete(int listEntryId) {
        String mutation = "mutation($id:Int!){ DeleteMediaListEntry(id:$id){ deleted } }";
        return client.execute(mutation, Map.of("id", listEntryId))
                .thenApply(root -> root.path("data").path("DeleteMediaListEntry").path("deleted").asBoolean(false));
    }

    private static Map<String,Integer> dateInput(LocalDate date) {
        return date == null ? null : Map.of("year", date.getYear(), "month", date.getMonthValue(), "day", date.getDayOfMonth());
    }

    private static UserListEntry parse(JsonNode n) {
        MediaListStatus status = null;
        try { status = MediaListStatus.valueOf(n.path("status").asText("")); } catch (Exception ignored) {}
        java.util.ArrayList<String> custom = new java.util.ArrayList<>();
        if (n.path("customLists").isObject()) {
            n.path("customLists").fields().forEachRemaining(e -> { if (e.getValue().asBoolean(false)) custom.add(e.getKey()); });
        }
        return new UserListEntry(
                n.path("id").isNumber() ? n.path("id").asInt() : null,
                status,
                n.path("progress").asInt(0),
                n.path("score").asDouble(0),
                n.path("repeat").asInt(0),
                n.path("priority").asInt(0),
                n.path("private").asBoolean(false),
                n.path("notes").asText(""),
                custom,
                date(n.path("startedAt")),
                date(n.path("completedAt"))
        );
    }

    private static LocalDate date(JsonNode n) {
        if (!n.path("year").isNumber()) return null;
        try { return LocalDate.of(n.path("year").asInt(), Math.max(1,n.path("month").asInt(1)), Math.max(1,n.path("day").asInt(1))); }
        catch (Exception ignored) { return null; }
    }
}
