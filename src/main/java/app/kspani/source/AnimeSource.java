package app.kspani.source;

import app.kspani.domain.AniMedia;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Clean-room provider boundary modeled after Saikou's BaseParser/AnimeParser lifecycle:
 * search/restore show -> load episodes -> load video servers -> resolve server.
 */
public interface AnimeSource {
    SourceDescriptor descriptor();

    boolean isConfigured();

    /** Optional host aliases used by provider-directory status matching. */
    default List<String> providerDomains() { return List.of(); }

    CompletableFuture<List<SourceSeries>> search(AniMedia media);

    CompletableFuture<List<SourceEpisode>> loadEpisodes(AniMedia media, SourceSeries series);

    /**
     * Selection-aware episode hook. Sources with separate Sub/Dub catalogs may override this
     * while simple sources inherit the normal episode loader.
     */
    default CompletableFuture<List<SourceEpisode>> loadEpisodes(
            AniMedia media,
            SourceSeries series,
            SourceSelection selection
    ) {
        return loadEpisodes(media, series);
    }

    /**
     * Optional last-chance playback hook for sources that can resolve a single AniList-indexed
     * episode without first exposing a durable provider series match. The returned match must be
     * ephemeral and must still be validated by the provider/player before playback is shown.
     */
    default CompletableFuture<EpisodeLoadResult> preparePlaybackFromIndex(
            AniMedia media,
            SourceEpisode indexedEpisode,
            SourceSelection selection
    ) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException(
                descriptor().name() + " does not support lazy episode resolution."));
    }

    CompletableFuture<List<VideoServer>> loadVideoServers(AniMedia media, SourceSeries series, SourceEpisode episode);

    CompletableFuture<ResolvedServer> resolveServer(
            AniMedia media,
            SourceSeries series,
            SourceEpisode episode,
            VideoServer server
    );
}
