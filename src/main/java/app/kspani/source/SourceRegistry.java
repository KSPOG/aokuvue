package app.kspani.source;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class SourceRegistry {
    private final Map<String, AnimeSource> sources = new LinkedHashMap<>();

    public SourceRegistry register(AnimeSource source) {
        if (source == null) return this;
        String id = source.descriptor().id();
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Anime source ID cannot be blank.");
        }
        sources.put(id, source);
        return this;
    }

    public SourceRegistry registerAll(java.util.Collection<? extends AnimeSource> additional) {
        if (additional != null) additional.forEach(this::register);
        return this;
    }

    public Optional<AnimeSource> get(String id) {
        return Optional.ofNullable(sources.get(id));
    }

    public List<AnimeSource> all() {
        return sources.values().stream()
                .sorted(Comparator.comparingInt(s -> s.descriptor().priority()))
                .toList();
    }

    public List<AnimeSource> configured() {
        return all().stream().filter(AnimeSource::isConfigured).toList();
    }

    public List<AnimeSource> playbackSources() {
        return all().stream()
                .filter(AnimeSource::isConfigured)
                .filter(s -> s.descriptor().capabilities().directPlayback())
                .toList();
    }

    /** User-visible playback providers only. Internal tracker/index providers stay hidden. */
    public List<AnimeSource> userVisibleSources() {
        return all().stream()
                .filter(s -> !"tracking".equals(s.descriptor().id()))
                .filter(s -> s.descriptor().capabilities().directPlayback())
                .toList();
    }

    public List<AnimeSource> configuredUserVisibleSources() {
        return userVisibleSources().stream().filter(AnimeSource::isConfigured).toList();
    }
}
