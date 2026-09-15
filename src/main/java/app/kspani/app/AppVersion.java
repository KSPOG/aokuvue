package app.kspani.app;

/** Resolves the version of the currently running Aokuvue build. */
public final class AppVersion {
    private static final String DEVELOPMENT_FALLBACK = "1.5.26";
    private static final String RELEASE_CHANNEL = "Beta";
    private static final String CHANNEL_VERSION = "0.0.4";

    private AppVersion() {}

    /** Runtime/package version used by update compatibility checks. */
    public static String current() {
        String explicit = System.getProperty("aokuvue.version", "").trim();
        if (!explicit.isBlank()) return explicit;

        Package pkg = AppVersion.class.getPackage();
        if (pkg != null) {
            String implementationVersion = pkg.getImplementationVersion();
            if (implementationVersion != null && !implementationVersion.isBlank()) {
                return implementationVersion.trim();
            }
        }

        return DEVELOPMENT_FALLBACK;
    }

    /** Human-facing channel/version label shown in Settings > Appearance. */
    public static String display() {
        return RELEASE_CHANNEL + " - " + CHANNEL_VERSION;
    }

    public static String channel() {
        return RELEASE_CHANNEL;
    }

    public static String channelVersion() {
        return CHANNEL_VERSION;
    }
}
