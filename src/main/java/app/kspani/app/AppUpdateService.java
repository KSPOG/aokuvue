package app.kspani.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks the official Aokuvue repository for a newer application build and launches the
 * separately packaged installer/updater after verifying its GitHub release digest.
 */
public final class AppUpdateService {
    static final URI REMOTE_BUILD_GRADLE = URI.create(
            "https://raw.githubusercontent.com/KSPOG/aokuvue/main/build.gradle");
    static final URI RELEASES_API = URI.create(
            "https://api.github.com/repos/KSPOG/aokuvue/releases?per_page=30");
    static final String MIN_INSTALLER_VERSION = "1.1.0";

    private static final Pattern GRADLE_VERSION = Pattern.compile(
            "(?m)^\\s*version\\s*=\\s*['\"]([^'\"]+)['\"]\\s*$");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");
    private static final String INSTALLER_ASSET = "AokuvueInstaller.exe";

    private final ObjectMapper mapper;
    private final HttpClient http;

    public AppUpdateService(ObjectMapper mapper) {
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(12))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public record UpdateInfo(
            String currentVersion,
            String latestVersion,
            String installerVersion,
            URI installerUrl,
            String installerSha256
    ) {}

    /** Returns empty when the current build is current or a compatible updater is not published yet. */
    public CompletableFuture<Optional<UpdateInfo>> checkForUpdate() {
        if (!isWindows()) return CompletableFuture.completedFuture(Optional.empty());

        CompletableFuture<String> buildFile = fetchText(REMOTE_BUILD_GRADLE);
        CompletableFuture<String> releases = fetchText(RELEASES_API);
        return buildFile.thenCombine(releases, (gradle, releaseJson) -> {
            String current = AppVersion.current();
            String latest = extractGradleVersion(gradle);
            if (!isNewer(latest, current)) return null;

            InstallerRelease installer = findInstallerRelease(releaseJson)
                    .filter(value -> compareVersions(value.version(), MIN_INSTALLER_VERSION) >= 0)
                    .orElse(null);
            return installer == null ? null : new UpdateCandidate(current, latest, installer);
        }).thenCompose(candidate -> {
            if (candidate == null) return CompletableFuture.completedFuture(Optional.empty());
            InstallerRelease installer = candidate.installer();
            if (SHA256.matcher(installer.sha256()).matches()) return CompletableFuture.completedFuture(Optional.of(toUpdateInfo(candidate, installer.sha256())));
            if (installer.checksumUrl() == null) return CompletableFuture.completedFuture(Optional.empty());
            return fetchText(installer.checksumUrl()).thenApply(text -> {
                String sha = extractSha256(text);
                return SHA256.matcher(sha).matches() ? Optional.of(toUpdateInfo(candidate, sha)) : Optional.empty();
            });
        });
    }

    private static UpdateInfo toUpdateInfo(UpdateCandidate candidate, String sha256) {
        return new UpdateInfo(candidate.current(), candidate.latest(), candidate.installer().version(), candidate.installer().url(), sha256);
    }

    /**
     * Downloads and verifies the updater, launches it, and returns once its process has started.
     * The caller should then close Aokuvue; the updater waits for this process ID before replacing files.
     */
    public CompletableFuture<Path> downloadAndLaunch(UpdateInfo info, Path installDirectory) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Path updaterDirectory = updaterDirectory();
                Files.createDirectories(updaterDirectory);
                Path destination = updaterDirectory.resolve(INSTALLER_ASSET);
                Path partial = updaterDirectory.resolve(INSTALLER_ASSET + ".part");
                Files.deleteIfExists(partial);

                if (info.installerSha256() == null || !SHA256.matcher(info.installerSha256()).matches()) {
                    throw new IOException("The published updater does not have a valid SHA-256 digest");
                }
                download(info.installerUrl(), partial);
                String actualSha256 = sha256(partial);
                if (!actualSha256.equalsIgnoreCase(info.installerSha256())) {
                    Files.deleteIfExists(partial);
                    throw new IOException("Updater SHA-256 verification failed");
                }

                try {
                    Files.move(partial, destination,
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(partial, destination, StandardCopyOption.REPLACE_EXISTING);
                }

                long pid = ProcessHandle.current().pid();
                new ProcessBuilder(
                        destination.toString(),
                        "--update",
                        "--install-dir", installDirectory.toAbsolutePath().normalize().toString(),
                        "--wait-pid", Long.toString(pid)
                )
                        .directory(updaterDirectory.toFile())
                        .start();
                return destination;
            } catch (IOException e) {
                throw new IllegalStateException("Unable to prepare Aokuvue update", e);
            }
        });
    }

    public Path detectInstallDirectory() {
        String explicit = System.getProperty("aokuvue.installDir", "").trim();
        if (!explicit.isBlank()) return Path.of(explicit).toAbsolutePath().normalize();

        Optional<String> command = ProcessHandle.current().info().command();
        if (command.isPresent()) {
            try {
                Path executable = Path.of(command.get()).toAbsolutePath().normalize();
                String name = executable.getFileName() == null
                        ? ""
                        : executable.getFileName().toString().toLowerCase(Locale.ROOT);
                if (name.equals("aokuvue.exe") || name.equals("aokuvue")) {
                    Path parent = executable.getParent();
                    if (parent != null) return parent;
                }
            } catch (RuntimeException ignored) {
                // Try the bundled jpackage runtime path next.
            }
        }

        // jpackage app-images normally place java.home at <install>/runtime. This preserves a
        // custom installation path even if ProcessHandle reports the bundled java executable.
        try {
            String javaHome = System.getProperty("java.home", "").trim();
            if (!javaHome.isBlank()) {
                Path runtime = Path.of(javaHome).toAbsolutePath().normalize();
                Path parent = runtime.getParent();
                if (parent != null && Files.isRegularFile(parent.resolve("Aokuvue.exe"))) {
                    return parent;
                }
            }
        } catch (RuntimeException ignored) {
            // Fall through to the normal Windows per-user default.
        }

        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            return Path.of(localAppData, "Programs", "Aokuvue").toAbsolutePath().normalize();
        }
        return Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
    }

    static String extractGradleVersion(String text) {
        Matcher matcher = GRADLE_VERSION.matcher(text == null ? "" : text);
        if (!matcher.find()) throw new IllegalArgumentException("Remote Aokuvue version was not found");
        return matcher.group(1).trim();
    }

    static boolean isNewer(String candidate, String current) {
        return compareVersions(candidate, current) > 0;
    }

    static int compareVersions(String left, String right) {
        List<Integer> a = numericVersion(left);
        List<Integer> b = numericVersion(right);
        int count = Math.max(a.size(), b.size());
        for (int i = 0; i < count; i++) {
            int av = i < a.size() ? a.get(i) : 0;
            int bv = i < b.size() ? b.get(i) : 0;
            int compared = Integer.compare(av, bv);
            if (compared != 0) return compared;
        }
        return 0;
    }

    static String extractSha256(String text) {
        Matcher matcher = SHA256.matcher(text == null ? "" : text);
        return matcher.find() ? matcher.group().toLowerCase(Locale.ROOT) : "";
    }

    private Optional<InstallerRelease> findInstallerRelease(String json) {
        try {
            JsonNode releases = mapper.readTree(json);
            InstallerRelease best = null;
            if (!releases.isArray()) return Optional.empty();

            for (JsonNode release : releases) {
                if (release.path("draft").asBoolean(false) || release.path("prerelease").asBoolean(false)) continue;
                String tag = release.path("tag_name").asText("");
                if (!tag.toLowerCase(Locale.ROOT).startsWith("installer-v")) continue;
                String version = tag.substring("installer-v".length()).trim();

                URI checksumUrl = null;
                for (JsonNode asset : release.path("assets")) {
                    if ((INSTALLER_ASSET + ".sha256").equalsIgnoreCase(asset.path("name").asText(""))) {
                        String checksum = asset.path("browser_download_url").asText("").trim();
                        if (!checksum.isBlank()) checksumUrl = URI.create(checksum);
                    }
                }
                for (JsonNode asset : release.path("assets")) {
                    if (!INSTALLER_ASSET.equalsIgnoreCase(asset.path("name").asText(""))) continue;
                    String url = asset.path("browser_download_url").asText("").trim();
                    if (url.isBlank()) continue;
                    String digest = asset.path("digest").asText("").trim();
                    String sha = digest.toLowerCase(Locale.ROOT).startsWith("sha256:")
                            ? digest.substring("sha256:".length()).trim()
                            : "";
                    if (!SHA256.matcher(sha).matches()) continue;
                    InstallerRelease candidate = new InstallerRelease(version, URI.create(url), sha, checksumUrl);
                    if (best == null || compareVersions(candidate.version(), best.version()) > 0) best = candidate;
                }
            }
            return Optional.ofNullable(best);
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to parse Aokuvue installer releases", e);
        }
    }

    private CompletableFuture<String> fetchText(URI uri) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "*/*")
                .header("User-Agent", "Aokuvue/" + AppVersion.current())
                .GET()
                .build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        throw new IllegalStateException("Update request failed with HTTP " + response.statusCode());
                    }
                    return response.body();
                });
    }

    private void download(URI uri, Path destination) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(3))
                .header("Accept", "application/octet-stream")
                .header("User-Agent", "Aokuvue/" + AppVersion.current())
                .GET()
                .build();
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("Updater download failed with HTTP " + response.statusCode());
            }
            try (InputStream in = response.body()) {
                Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Updater download was interrupted", e);
        }
    }

    private static Path updaterDirectory() {
        String localAppData = System.getenv("LOCALAPPDATA");
        Path base = localAppData != null && !localAppData.isBlank()
                ? Path.of(localAppData, "Aokuvue")
                : Path.of(System.getProperty("user.home"), ".aokuvue");
        return base.resolve("updater");
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    if (read > 0) digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static List<Integer> numericVersion(String value) {
        String normalized = value == null ? "" : value.trim().replaceFirst("^[vV]", "");
        Matcher matcher = Pattern.compile("\\d+").matcher(normalized);
        List<Integer> parts = new ArrayList<>();
        while (matcher.find() && parts.size() < 4) {
            try {
                parts.add(Integer.parseInt(matcher.group()));
            } catch (NumberFormatException ignored) {
                parts.add(0);
            }
        }
        if (parts.isEmpty()) parts.add(0);
        return parts;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private record UpdateCandidate(String current, String latest, InstallerRelease installer) {}
    private record InstallerRelease(String version, URI url, String sha256, URI checksumUrl) {}
}
