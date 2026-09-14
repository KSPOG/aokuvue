package app.kspani.app;

/** Resolves the version of the currently running Aokuvue build. */
public final class AppVersion {
    private static final String DEVELOPMENT_FALLBACK = "1.5.23";

    private AppVersion() {}

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
}
