package app.kspani.source;

import java.util.List;
import java.util.Map;

/** Clean-room analogue of Saikou's durable ShowResponse concept. */
public record SourceSeries(
        String providerId,
        String seriesId,
        String name,
        String link,
        String coverUrl,
        List<String> otherNames,
        Integer total,
        Map<String, String> extra
) {
    public SourceSeries {
        otherNames = otherNames == null ? List.of() : List.copyOf(otherNames);
        extra = extra == null ? Map.of() : Map.copyOf(extra);
    }
}
