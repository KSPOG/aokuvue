package app.kspani.domain;

import java.time.LocalDate;
import java.util.List;

public record UserListEntry(
        Integer id,
        MediaListStatus status,
        int progress,
        double score,
        int repeat,
        int priority,
        boolean privateEntry,
        String notes,
        List<String> customLists,
        LocalDate startedAt,
        LocalDate completedAt
) {
    public static UserListEntry empty() {
        return new UserListEntry(null, null, 0, 0.0, 0, 0, false, "", List.of(), null, null);
    }

    public boolean onList() {
        return id != null || status != null;
    }
}
