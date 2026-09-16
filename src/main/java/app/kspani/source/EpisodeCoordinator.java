package app.kspani.source;

import app.kspani.domain.AniMedia;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The single episode pipeline.
 *
 * Saikou-style lifecycle:
 * selected source -> saved show match / source search -> source episode list -> selected episode
 * -> source video servers -> resolve selected server -> player.
 *
 * The coordinator remains the provider boundary and automatically falls through configured
 * EverythingMoe adapters in priority order when a source cannot resolve the selected title.
 */
public final class EpisodeCoordinator {
    private final SourceRegistry registry;
    private final SourceStateRepository state;
    private final java.util.function.Supplier<String> defaultSourceId;
    private final java.util.function.Supplier<PlaybackLanguage> defaultLanguagePreference;

    public EpisodeCoordinator(
            SourceRegistry registry,
            SourceStateRepository state,
            java.util.function.Supplier<String> defaultSourceId,
            java.util.function.Supplier<PlaybackLanguage> defaultLanguagePreference
    ) {
        this.registry = registry;
        this.state = state;
        this.defaultSourceId = defaultSourceId;
        this.defaultLanguagePreference = defaultLanguagePreference;
    }

    public SourceSelection selectionFor(AniMedia media) {
        if (registry.configured().isEmpty()) {
            return SourceSelection.defaults(media.id(), "", defaultLanguagePreference.get());
        }
        SourceSelection selection = state.loadSelection(media.id())
                .orElseGet(() -> SourceSelection.defaults(
                        media.id(), chooseDefaultSource(), defaultLanguagePreference.get()));

        boolean invalid = registry.get(selection.sourceId()).filter(AnimeSource::isConfigured).isEmpty();
        boolean legacyTrackingSelection = "tracking".equals(selection.sourceId()) && !registry.playbackSources().isEmpty();
        if (invalid || legacyTrackingSelection) {
            selection = selection.withSource(chooseDefaultSource());
            state.saveSelection(selection);
        }
        return selection;
    }

    public SourceSelection selectSource(AniMedia media, String sourceId) {
        AnimeSource source = registry.get(sourceId)
                .filter(AnimeSource::isConfigured)
                .orElseThrow(() -> new IllegalArgumentException("Source is not configured: " + sourceId));
        SourceSelection selection = selectionFor(media).withSource(source.descriptor().id());
        state.saveSelection(selection);
        return selection;
    }

    public CompletableFuture<EpisodeLoadResult> loadEpisodes(AniMedia media, boolean rematch) {
        SourceSelection selection = selectionFor(media);
        AnimeSource source = registry.get(selection.sourceId())
                .filter(AnimeSource::isConfigured)
                .orElse(null);
        if (source == null) return noSources();
        if (rematch) state.clearSeriesMatch(media.id(), source.descriptor().id());
        return loadWithSource(media, source, true);
    }

    public CompletableFuture<EpisodeLoadResult> loadEpisodes(AniMedia media, String sourceId, boolean rematch) {
        selectSource(media, sourceId);
        if (rematch) state.clearSeriesMatch(media.id(), sourceId);
        AnimeSource source = registry.get(sourceId)
                .filter(AnimeSource::isConfigured)
                .orElse(null);
        if (source == null) return noSources();
        return loadWithSource(media, source, true);
    }

    private CompletableFuture<EpisodeLoadResult> loadWithSource(
            AniMedia media,
            AnimeSource source,
            boolean allowTrackingFallback
    ) {
        CompletableFuture<EpisodeLoadResult> attempt = loadWithSourceStrict(media, source);

        if (!allowTrackingFallback) {
            return attempt;
        }

        return attempt.handle((loaded, error) -> {
            if (error == null && loaded != null && !loaded.episodes().isEmpty()) {
                return CompletableFuture.completedFuture(loaded);
            }

            // A saved provider match can become stale. Clear only that provider's match, then
            // try every other configured playback provider before dropping to the tracker index.
            state.clearSeriesMatch(media.id(), source.descriptor().id());
            List<AnimeSource> alternatives = registry.playbackSources().stream()
                    .filter(candidate -> !candidate.descriptor().id().equals(source.descriptor().id()))
                    .toList();
            return tryPlaybackFallbacks(media, alternatives, 0);
        }).thenCompose(java.util.function.Function.identity());
    }

    private CompletableFuture<EpisodeLoadResult> tryPlaybackFallbacks(
            AniMedia media,
            List<AnimeSource> alternatives,
            int index
    ) {
        if (alternatives == null || index >= alternatives.size()) {
            return noSources();
        }

        AnimeSource candidate = alternatives.get(index);
        return loadWithSourceStrict(media, candidate).handle((loaded, error) -> {
            if (error == null && loaded != null && !loaded.episodes().isEmpty()) {
                // A working playback provider is more useful than retaining a known-dead selection.
                // Preserve language preference while resetting provider-specific server/video state.
                SourceSelection updated = selectionFor(media).withSource(candidate.descriptor().id());
                state.saveSelection(updated);
                return CompletableFuture.completedFuture(loaded);
            }
            state.clearSeriesMatch(media.id(), candidate.descriptor().id());
            return tryPlaybackFallbacks(media, alternatives, index + 1);
        }).thenCompose(java.util.function.Function.identity());
    }

    private CompletableFuture<EpisodeLoadResult> loadWithSourceStrict(AniMedia media, AnimeSource source) {
        var restored = state.loadSeriesMatch(media.id(), source.descriptor().id());
        if (restored.isPresent()) {
            SourceSeries series = restored.get();

            // Provider matches are durable, but must still meet the normal automatic-match
            // threshold when restored. Explicit user overrides are preserved.
            boolean manualOverride = "true".equalsIgnoreCase(series.extra().getOrDefault("manualOverride", "false"));
            double restoredConfidence = TitleMatcher.score(media, series);
            double minimumConfidence = autoMatchThreshold(source);
            if (!manualOverride && restoredConfidence < minimumConfidence) {
                state.clearSeriesMatch(media.id(), source.descriptor().id());
                return loadWithSourceStrict(media, source);
            }

            SourceSelection selection = selectionFor(media);
            return source.loadEpisodes(media, series, selection)
                    .thenApply(episodes -> {
                        List<SourceEpisode> normalized = EpisodeLanguageSelector.filterEpisodes(
                                EpisodeNormalizer.normalize(episodes), selection.languagePreference());
                        if (normalized.isEmpty()) {
                            throw new IllegalStateException(
                                    source.descriptor().name() + " returned no episodes for the saved series match.");
                        }
                        return new EpisodeLoadResult(
                                source,
                                series,
                                normalized,
                                true,
                                List.of()
                        );
                    });
        }

        return source.search(media).thenCompose(candidates -> {
            if (candidates == null || candidates.isEmpty()) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException(source.descriptor().name() + " could not match this title."));
            }

            List<SourceSeries> ranked = TitleMatcher.ranked(media, candidates);
            SourceSeries selected = ranked.get(0);
            double confidence = TitleMatcher.score(media, selected);
            if (confidence < autoMatchThreshold(source)) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        source.descriptor().name()
                                + " found results, but none matched this AniList title confidently."));
            }

            state.saveSeriesMatch(media.id(), selected);
            List<SourceSeries> alternatives = ranked.size() <= 1
                    ? List.of()
                    : ranked.subList(1, Math.min(6, ranked.size()));
            SourceSelection selection = selectionFor(media);

            return source.loadEpisodes(media, selected, selection)
                    .thenApply(episodes -> {
                        List<SourceEpisode> normalized = EpisodeLanguageSelector.filterEpisodes(
                                EpisodeNormalizer.normalize(episodes), selection.languagePreference());
                        if (normalized.isEmpty()) {
                            throw new IllegalStateException(
                                    source.descriptor().name() + " matched " + selected.name()
                                            + " but returned no episodes.");
                        }
                        return new EpisodeLoadResult(
                                source,
                                selected,
                                normalized,
                                false,
                                alternatives
                        );
                    });
        });
    }

    private static double autoMatchThreshold(AnimeSource source) {
        return 0.55;
    }

    public SourceSelection setPreferDub(AniMedia media, boolean preferDub) {
        return setLanguagePreference(media, preferDub ? PlaybackLanguage.ENGLISH_DUB : PlaybackLanguage.ENGLISH_SUB);
    }

    public SourceSelection setLanguagePreference(AniMedia media, PlaybackLanguage language) {
        SourceSelection updated = selectionFor(media).withLanguage(language);
        state.saveSelection(updated);
        return updated;
    }

    public void overrideSeriesMatch(AniMedia media, SourceSeries series) {
        if (media == null || series == null) return;
        java.util.Map<String, String> extra = new java.util.LinkedHashMap<>(series.extra());
        extra.put("manualOverride", "true");
        SourceSeries explicit = new SourceSeries(
                series.providerId(), series.seriesId(), series.name(), series.link(), series.coverUrl(),
                series.otherNames(), series.total(), extra);
        state.saveSeriesMatch(media.id(), explicit);
    }

    public CompletableFuture<List<VideoServer>> loadVideoServers(
            AniMedia media,
            EpisodeLoadResult loaded,
            SourceEpisode episode
    ) {
        if (!loaded.source().descriptor().capabilities().directPlayback()) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    loaded.source().descriptor().name()
                            + " provides the episode index only; no direct playback source is configured."));
        }
        return loaded.source().loadVideoServers(media, loaded.series(), episode)
                .thenApply(servers -> servers == null ? List.of() : List.copyOf(servers));
    }

    public CompletableFuture<List<VideoServer>> loadVideoServers(
            AniMedia media,
            PlaybackResolution playback
    ) {
        if (playback == null || playback.source() == null || playback.series() == null
                || playback.episode() == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "Resolved playback context is incomplete."));
        }
        return playback.source().loadVideoServers(media, playback.series(), playback.episode())
                .thenApply(servers -> servers == null ? List.of() : List.copyOf(servers));
    }

    public CompletableFuture<PlaybackResolution> resolveEpisode(
            AniMedia media,
            EpisodeLoadResult loaded,
            SourceEpisode episode
    ) {
        SourceSelection selection = selectionFor(media);
        if (loaded.source().descriptor().capabilities().directPlayback()) {
            return resolveEpisodeFromLoaded(media, loaded, episode, selection);
        }

        // The internal AniList episode index is only a catalog fallback. Do not disable Watch.
        // Resolve the requested episode lazily through the user's selected playback provider, then
        // through the remaining configured providers. This keeps transient indexing/search failures
        // from turning every episode into a dead row.
        List<AnimeSource> ordered = orderedPlaybackSources(selection.sourceId());
        return tryResolveIndexedEpisode(media, episode, selection, ordered, 0);
    }

    private CompletableFuture<PlaybackResolution> resolveEpisodeFromLoaded(
            AniMedia media,
            EpisodeLoadResult loaded,
            SourceEpisode episode,
            SourceSelection selection
    ) {
        return loadVideoServers(media, loaded, episode).thenCompose(servers -> {
            if (servers.isEmpty()) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "No video server is available for Episode " + episode.number()));
            }
            List<VideoServer> ranked = EpisodeLanguageSelector.rankServers(
                    servers, selection.languagePreference(), selection.serverName());
            return tryResolveServers(media, loaded, episode, ranked, 0, new java.util.ArrayList<>());
        });
    }

    private CompletableFuture<PlaybackResolution> tryResolveServers(
            AniMedia media,
            EpisodeLoadResult loaded,
            SourceEpisode episode,
            List<VideoServer> servers,
            int index,
            List<String> failures
    ) {
        if (index >= servers.size()) {
            String detail = failures.isEmpty() ? "no server details" : String.join("; ", failures);
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "All " + servers.size() + " video server(s) failed for Episode "
                            + episode.number() + ": " + detail));
        }
        VideoServer server = servers.get(index);
        return resolveServer(media, loaded, episode, server).handle((resolved, error) -> {
            if (error == null && resolved != null) {
                return CompletableFuture.completedFuture(resolved);
            }
            String reason = rootMessage(error);
            failures.add(server.name() + " — " + reason);
            System.err.println("[Aokuvue][Playback] server rejected"
                    + " mediaId=" + media.id() + " episode=" + episode.number()
                    + " source=" + loaded.source().descriptor().id()
                    + " server=" + server.name() + " reason=" + reason);
            return tryResolveServers(media, loaded, episode, servers, index + 1, failures);
        }).thenCompose(java.util.function.Function.identity());
    }

    private static String rootMessage(Throwable error) {
        if (error == null) return "unknown failure";
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String message = root.getMessage();
        return message == null || message.isBlank() ? root.getClass().getSimpleName() : message;
    }

    private CompletableFuture<PlaybackResolution> tryResolveIndexedEpisode(
            AniMedia media,
            SourceEpisode indexedEpisode,
            SourceSelection selection,
            List<AnimeSource> sources,
            int index
    ) {
        if (sources == null || index >= sources.size()) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "No configured playback source could resolve Episode " + indexedEpisode.number() + "."));
        }

        AnimeSource source = sources.get(index);
        return preparePlaybackForIndexedEpisode(media, source, indexedEpisode, selection)
                .thenCompose(prepared -> {
                    SourceEpisode providerEpisode = findEpisodeByNumber(prepared.episodes(), indexedEpisode);
                    if (providerEpisode == null) {
                        return CompletableFuture.failedFuture(new IllegalStateException(
                                source.descriptor().name() + " did not expose Episode " + indexedEpisode.number() + "."));
                    }
                    return resolveEpisodeFromLoaded(media, prepared, providerEpisode, selection);
                })
                .handle((resolved, error) -> {
                    if (error == null && resolved != null) {
                        SourceSelection updated = selectionFor(media).withSource(source.descriptor().id());
                        state.saveSelection(updated);
                        return CompletableFuture.completedFuture(resolved);
                    }
                    return tryResolveIndexedEpisode(media, indexedEpisode, selection, sources, index + 1);
                })
                .thenCompose(java.util.function.Function.identity());
    }

    private CompletableFuture<EpisodeLoadResult> preparePlaybackForIndexedEpisode(
            AniMedia media,
            AnimeSource source,
            SourceEpisode indexedEpisode,
            SourceSelection selection
    ) {
        // Prefer a source's dedicated lazy resolver before retrying its durable match flow.
        return source.preparePlaybackFromIndex(media, indexedEpisode, selection)
                .handle((lazy, lazyError) -> {
                    if (lazyError == null && lazy != null && !lazy.episodes().isEmpty()) {
                        return CompletableFuture.completedFuture(lazy);
                    }

                    // Sources without lazy resolution retry the normal durable provider match once.
                    return loadWithSourceStrict(media, source).handle((loaded, error) -> {
                        if (error == null && loaded != null && !loaded.episodes().isEmpty()) {
                            return CompletableFuture.completedFuture(loaded);
                        }
                        state.clearSeriesMatch(media.id(), source.descriptor().id());
                        return loadWithSourceStrict(media, source);
                    }).thenCompose(java.util.function.Function.identity());
                })
                .thenCompose(java.util.function.Function.identity());
    }

    private List<AnimeSource> orderedPlaybackSources(String preferredSourceId) {
        List<AnimeSource> all = registry.playbackSources();
        if (preferredSourceId == null || preferredSourceId.isBlank()) return all;
        return all.stream()
                .sorted(Comparator.comparingInt(source ->
                        source.descriptor().id().equals(preferredSourceId) ? 0 : 1))
                .toList();
    }

    private static SourceEpisode findEpisodeByNumber(List<SourceEpisode> episodes, SourceEpisode requested) {
        if (episodes == null || episodes.isEmpty() || requested == null) return null;
        double wanted = requested.numericNumber();
        for (SourceEpisode candidate : episodes) {
            if (candidate == null) continue;
            if (candidate.number() != null && requested.number() != null
                    && candidate.number().trim().equalsIgnoreCase(requested.number().trim())) return candidate;
            double actual = candidate.numericNumber();
            if (!Double.isNaN(wanted) && !Double.isNaN(actual) && Math.abs(wanted - actual) < 0.0001) return candidate;
        }
        return null;
    }

    public CompletableFuture<PlaybackResolution> resolveServer(
            AniMedia media,
            EpisodeLoadResult loaded,
            SourceEpisode episode,
            VideoServer chosen
    ) {
        if (chosen == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Video server is required."));
        }
        SourceSelection selection = selectionFor(media);
        SourceSelection updated = selection.withServer(chosen.name()).withEpisode(episode.number());
        state.saveSelection(updated);
        return loaded.source().resolveServer(media, loaded.series(), episode, chosen)
                .thenApply(resolved -> selectPlayback(media, loaded, episode, resolved, updated));
    }

    public CompletableFuture<PlaybackResolution> resolveServer(
            AniMedia media,
            PlaybackResolution current,
            VideoServer chosen
    ) {
        if (current == null || current.source() == null || current.series() == null
                || current.episode() == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "Resolved playback context is incomplete."));
        }
        if (chosen == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Video server is required."));
        }
        SourceSelection selection = selectionFor(media);
        SourceSelection updated = selection.withServer(chosen.name()).withEpisode(current.episode().number());
        state.saveSelection(updated);
        return current.source().resolveServer(media, current.series(), current.episode(), chosen)
                .thenApply(resolved -> selectPlayback(
                        media, current.source(), current.series(), current.episode(), resolved, updated));
    }

    public SourceSelection setVideoIndex(AniMedia media, int videoIndex) {
        SourceSelection updated = selectionFor(media).withVideoIndex(videoIndex);
        state.saveSelection(updated);
        return updated;
    }

    public SourceSelection setPreferredQuality(AniMedia media, Integer quality, int fallbackIndex) {
        SourceSelection updated = selectionFor(media).withVideoIndex(fallbackIndex);
        state.saveSelection(updated);
        state.savePreferredQuality(media.id(), quality);
        return updated;
    }

    public SourceSelection setSubtitleIndex(AniMedia media, Integer subtitleIndex) {
        SourceSelection updated = selectionFor(media).withSubtitleIndex(subtitleIndex);
        state.saveSelection(updated);
        return updated;
    }

    private PlaybackResolution selectPlayback(
            AniMedia media,
            EpisodeLoadResult loaded,
            SourceEpisode episode,
            ResolvedServer resolved,
            SourceSelection selection
    ) {
        return selectPlayback(media, loaded.source(), loaded.series(), episode, resolved, selection);
    }

    private PlaybackResolution selectPlayback(
            AniMedia media,
            AnimeSource source,
            SourceSeries series,
            SourceEpisode episode,
            ResolvedServer resolved,
            SourceSelection selection
    ) {
        if (resolved == null || !resolved.playable()) {
            throw new IllegalStateException("The selected server returned no playable media.");
        }
        List<PlaybackSource> videos = EpisodeLanguageSelector.rankVideos(resolved.videos(), selection.languagePreference());
        int index = preferredQualityIndex(videos, state.loadPreferredQuality(media.id()).orElse(null), selection.videoIndex());
        PlaybackSource selectedVideo = videos.get(index);
        Integer subtitleIndex = selection.subtitleIndex();
        if (selection.languagePreference() == PlaybackLanguage.ENGLISH_SUB) {
            subtitleIndex = EpisodeLanguageSelector.englishSubtitleIndex(resolved.subtitles(), subtitleIndex);
        } else if (selection.languagePreference() == PlaybackLanguage.ENGLISH_DUB) {
            subtitleIndex = null;
        } else {
            subtitleIndex = EpisodeLanguageSelector.defaultSubtitleIndex(
                    resolved.subtitles(), subtitleIndex);
        }
        return new PlaybackResolution(
                source, series, episode, resolved,
                selectedVideo, index, subtitleIndex
        );
    }

    private static int preferredQualityIndex(List<PlaybackSource> videos, Integer preferredQuality, int fallbackIndex) {
        if (videos == null || videos.isEmpty()) return 0;
        if (preferredQuality != null) {
            int exact = -1;
            int closest = -1;
            int distance = Integer.MAX_VALUE;
            for (int i=0;i<videos.size();i++) {
                Integer q=videos.get(i).quality();
                if(q==null)continue;
                if(q.equals(preferredQuality)){exact=i;break;}
                int d=Math.abs(q-preferredQuality);
                if(d<distance){distance=d;closest=i;}
            }
            if(exact>=0)return exact;
            if(closest>=0)return closest;
        }
        return Math.max(0,Math.min(fallbackIndex,videos.size()-1));
    }

    private static VideoServer chooseServer(List<VideoServer> servers, String savedName) {
        if (savedName != null && !savedName.isBlank()) {
            for (VideoServer server : servers) {
                if (server.name().equalsIgnoreCase(savedName)) return server;
            }
        }
        return servers.get(0);
    }

    private String chooseDefaultSource() {
        String requested = defaultSourceId.get();
        if (requested != null && !requested.isBlank()
                && registry.get(requested)
                        .filter(AnimeSource::isConfigured)
                        .filter(s -> s.descriptor().capabilities().directPlayback())
                        .isPresent()) {
            return requested;
        }

        return registry.playbackSources().stream().findFirst()
                .or(() -> registry.all().stream().filter(AnimeSource::isConfigured).findFirst())
                .map(s -> s.descriptor().id())
                .orElse("");
    }

    private static <T> CompletableFuture<T> noSources() {
        return CompletableFuture.failedFuture(new IllegalStateException(
                "No episode or playback sources are registered."));
    }

}
