package app.kspani.app;

import app.kspani.anilist.AniListAuthService;
import app.kspani.anilist.AniListClient;
import app.kspani.anilist.AniListListService;
import app.kspani.config.AppConfig;
import app.kspani.data.AppDatabase;
import app.kspani.player.PlaybackProgressRepository;
import app.kspani.player.PlayerController;
import app.kspani.source.EpisodeCoordinator;
import app.kspani.source.SourceRegistry;
import app.kspani.source.PlaybackLanguage;
import app.kspani.source.EverythingMoeProviderDirectory;
import app.kspani.source.SourceStateRepository;
import app.kspani.sources.AnikotoAnimeSource;

public final class AppContext implements AutoCloseable {
    private final AppConfig config;
    private final JsonHttpClient http;
    private final AppDatabase database;
    private final AniListClient anilist;
    private final AniListAuthService auth;
    private final AniListListService listService;
    private final SourceRegistry sources;
    private final SourceStateRepository sourceState;
    private final EverythingMoeProviderDirectory providerDirectory;
    private final EpisodeCoordinator episodes;
    private final PlaybackProgressRepository playbackProgress;
    private final PlayerController player;
    private final FeedbackService feedback;

    private AppContext(
            AppConfig config,
            JsonHttpClient http,
            AppDatabase database,
            AniListClient anilist,
            AniListAuthService auth,
            AniListListService listService,
            SourceRegistry sources,
            SourceStateRepository sourceState,
            EverythingMoeProviderDirectory providerDirectory,
            EpisodeCoordinator episodes,
            PlaybackProgressRepository playbackProgress,
            PlayerController player,
            FeedbackService feedback
    ) {
        this.config = config;
        this.http = http;
        this.database = database;
        this.anilist = anilist;
        this.auth = auth;
        this.listService = listService;
        this.sources = sources;
        this.sourceState = sourceState;
        this.providerDirectory = providerDirectory;
        this.episodes = episodes;
        this.playbackProgress = playbackProgress;
        this.player = player;
        this.feedback = feedback;
    }

    public static AppContext create() {
        AppConfig config = AppConfig.load();
        JsonHttpClient http = new JsonHttpClient();
        AppDatabase database = new AppDatabase(config.dataDirectory().resolve("kspani.db"));
        database.initialize();
        AniListClient anilist = new AniListClient(config, http);
        AniListAuthService auth = new AniListAuthService(config);
        AniListListService listService = new AniListListService(anilist);

        SourceRegistry sources = createSourceRegistry(http);

        EverythingMoeProviderDirectory providerDirectory = new EverythingMoeProviderDirectory(sources);
        SourceStateRepository sourceState = new SourceStateRepository(database, http.mapper());
        EpisodeCoordinator episodes = new EpisodeCoordinator(
                sources,
                sourceState,
                () -> "anikoto",
                () -> PlaybackLanguage.AUTO
        );
        PlaybackProgressRepository playbackProgress = new PlaybackProgressRepository(database);
        PlayerController player = new PlayerController(config, playbackProgress, anilist, listService);
        FeedbackService feedback = new FeedbackService(http.mapper());

        return new AppContext(config, http, database, anilist, auth, listService, sources, sourceState, providerDirectory, episodes, playbackProgress, player, feedback);
    }

    static SourceRegistry createSourceRegistry(JsonHttpClient http) {
        return new SourceRegistry().register(new AnikotoAnimeSource(http));
    }

    public AppConfig config() { return config; }
    public JsonHttpClient http() { return http; }
    public AppDatabase database() { return database; }
    public AniListClient anilist() { return anilist; }
    public AniListAuthService auth() { return auth; }
    public AniListListService listService() { return listService; }
    public SourceRegistry sources() { return sources; }
    public SourceStateRepository sourceState() { return sourceState; }
    public EverythingMoeProviderDirectory providerDirectory() { return providerDirectory; }
    public EpisodeCoordinator episodes() { return episodes; }
    public PlaybackProgressRepository playbackProgress() { return playbackProgress; }
    public PlayerController player() { return player; }
    public FeedbackService feedback() { return feedback; }

    @Override
    public void close() {
        player.close();
        database.close();
    }
}
