package app.kspani.player;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loopback byte-range relay for provider media URLs that JavaFX cannot open directly.
 *
 * v1.5.9 deliberately exposes a media-looking localhost path (for example /media.mp4),
 * performs a real upstream range probe before JavaFX is started, and emulates HEAD from
 * GET/range metadata. Those details matter for JavaFX's native media pipeline on Windows.
 */
public final class HttpMediaRelay implements AutoCloseable {
    private static final String DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36";
    private static final Pattern CONTENT_RANGE = Pattern.compile("(?i)^bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)$");
    private static final Pattern FIXED_RANGE = Pattern.compile("(?i)^bytes=(\\d+)-(\\d+)$");
    private static final int IO_BUFFER_BYTES = 256 * 1024;
    private static final int PREFETCH_BYTES = 1024 * 1024;
    private static final int TRANSPORT_STREAM_PACKET_BYTES = 188;
    private static final int DISGUISED_SEGMENT_PROBE_BYTES = 4096;

    private final URI remoteUri;
    private final Map<String, String> upstreamHeaders;
    private final HttpClient client;
    private final HttpServer server;
    private final ExecutorService executor;
    private final URI localUri;
    private final String forcedContentType;
    private final boolean hls;
    private final AtomicInteger requestCount = new AtomicInteger();

    private volatile long knownLength = -1L;
    private volatile boolean rangeSupported = true;
    private volatile String observedContentType = "";
    private volatile boolean probed;
    private volatile IOException probeFailure;
    private volatile CachedBlock cachedBlock;

    private HttpMediaRelay(URI remoteUri, Map<String, String> upstreamHeaders, String container) throws IOException {
        this.remoteUri = remoteUri;
        this.upstreamHeaders = upstreamHeaders == null ? Map.of() : Map.copyOf(upstreamHeaders);
        this.forcedContentType = contentTypeFor(container, remoteUri);
        this.hls = forcedContentType.contains("mpegurl");
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                // HTTP/1.1 is more predictable for Range passthrough to GoogleVideo/Blogger CDN hosts.
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        // The upstream probe is performed lazily on the relay worker thread when JavaFX asks for
        // metadata. Never block the JavaFX application thread while constructing MediaPlayer.

        // Bind explicitly to IPv4 because the URI below is explicitly 127.0.0.1. Using
        // InetAddress.getLoopbackAddress() can select ::1 on Windows and leave JavaFX talking to
        // an address the server did not actually bind.
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "kspani-media-relay");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(executor);
        String extension = extensionFor(container, remoteUri);
        String contextPath = "/media" + extension;
        server.createContext(contextPath, this::handle);
        if (hls) server.createContext("/hls/", this::handleHlsAsset);
        server.start();
        this.localUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + contextPath);
        System.out.println("[Aokuvue][MediaRelay] READY local=" + localUri
                + " upstream=" + safeHost(remoteUri)
                + " type=" + effectiveContentType());
    }

    public static HttpMediaRelay start(URI remoteUri, Map<String, String> headers) {
        return start(remoteUri, headers, "");
    }

    public static HttpMediaRelay start(URI remoteUri, Map<String, String> headers, String container) {
        if (remoteUri == null || !remoteUri.isAbsolute()) {
            throw new IllegalArgumentException("A valid remote media URL is required.");
        }
        try {
            return new HttpMediaRelay(remoteUri, headers, container);
        } catch (IOException error) {
            throw new IllegalStateException("Could not start local media relay: " + error.getMessage(), error);
        }
    }

    public URI localUri() { return localUri; }

    private synchronized void ensureProbe() {
        if (probed) return;
        probed = true;
        try {
            Probe probe = probeUpstream();
            knownLength = probe.totalLength();
            rangeSupported = probe.rangeSupported();
            observedContentType = probe.contentType();
        } catch (IOException failure) {
            probeFailure = failure;
            System.err.println("[Aokuvue][MediaRelay] PROBE FAILED host=" + safeHost(remoteUri) + " reason=" + failure.getMessage());
        }
    }

    private Probe probeUpstream() throws IOException {
        HttpRequest request = upstreamRequest("GET", "bytes=0-1", null, Duration.ofSeconds(15));
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            String type = response.headers().firstValue("Content-Type").orElse("");
            String contentRange = response.headers().firstValue("Content-Range").orElse("");
            long total = totalLength(response, contentRange);
            boolean ranges = status == 206
                    || response.headers().firstValue("Accept-Ranges").orElse("").toLowerCase(Locale.ROOT).contains("bytes");
            try (InputStream in = response.body()) {
                // Force the connection to yield at least one byte when possible, then close it. This
                // verifies the signed URL is actually readable with the exact headers we will relay.
                in.readNBytes(2);
            }
            System.out.println("[Aokuvue][MediaRelay] PROBE status=" + status
                    + " host=" + safeHost(remoteUri)
                    + " type=" + type
                    + " contentRange=" + contentRange);
            if (status != 200 && status != 206) {
                throw new IOException("upstream probe returned HTTP " + status + " from " + safeHost(remoteUri));
            }
            return new Probe(status, type, total, ranges);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("upstream probe interrupted", interrupted);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        if (hls) {
            handleHlsTarget(exchange, remoteUri);
            return;
        }
        String method = exchange.getRequestMethod();
        if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }

        int requestNumber = requestCount.incrementAndGet();
        String downstreamRange = first(exchange.getRequestHeaders(), "Range");
        try {
            if ("HEAD".equalsIgnoreCase(method)) {
                // GoogleVideo/Blogger endpoints are not consistently useful for HEAD. JavaFX still
                // benefits from HEAD metadata, so synthesize it from a GET bytes=0-1 probe.
                ensureProbe();
                if (probeFailure != null) {
                    sendFailure(exchange, 502, "Upstream probe failed: " + probeFailure.getMessage());
                    return;
                }
                Headers responseHeaders = exchange.getResponseHeaders();
                applyStableResponseHeaders(responseHeaders);
                if (knownLength >= 0) responseHeaders.set("Content-Length", Long.toString(knownLength));
                if (requestNumber <= 12) {
                    System.out.println("[Aokuvue][MediaRelay] #" + requestNumber + " HEAD local -> 200"
                            + " length=" + knownLength + " type=" + effectiveContentType());
                }
                exchange.sendResponseHeaders(200, -1);
                return;
            }

            if (tryServePrefetchedRange(exchange, downstreamRange, requestNumber)) return;

            HttpRequest request = upstreamRequest("GET", downstreamRange, first(exchange.getRequestHeaders(), "If-Range"), Duration.ofSeconds(120));
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            String contentRange = response.headers().firstValue("Content-Range").orElse("");
            long responseLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            long total = totalLength(response, contentRange);
            if (total >= 0) knownLength = total;
            if (status == 206) rangeSupported = true;
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            if (!contentType.isBlank()) observedContentType = contentType;

            copyResponseHeaders(response, exchange.getResponseHeaders());
            applyStableResponseHeaders(exchange.getResponseHeaders());

            if (requestNumber <= 20 || status >= 400) {
                System.out.println("[Aokuvue][MediaRelay] #" + requestNumber
                        + " GET range=" + (downstreamRange.isBlank() ? "<none>" : downstreamRange)
                        + " -> upstream=" + status
                        + " bytes=" + responseLength
                        + " contentRange=" + contentRange
                        + " type=" + effectiveContentType());
            }

            if (status != 200 && status != 206) {
                try (InputStream in = response.body()) { in.readNBytes(512); }
                sendFailure(exchange, 502, "Upstream media returned HTTP " + status);
                return;
            }

            // A fixed response length is important for JavaFX seeking. If the upstream omitted
            // Content-Length but returned a bounded Content-Range, derive the body length from it.
            long bodyLength = responseLength;
            if (bodyLength < 0) bodyLength = rangeBodyLength(contentRange);
            exchange.sendResponseHeaders(status, bodyLength >= 0 ? bodyLength : 0);
            try (InputStream in = new BufferedInputStream(response.body(), IO_BUFFER_BYTES);
                 OutputStream out = new BufferedOutputStream(exchange.getResponseBody(), IO_BUFFER_BYTES)) {
                copyBuffered(in, out);
                out.flush();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            sendFailure(exchange, 502, "Media relay interrupted");
        } catch (Exception error) {
            System.err.println("[Aokuvue][MediaRelay] request failed: " + error);
            sendFailure(exchange, 502, "Media relay failed: " + error.getMessage());
        } finally {
            try { exchange.close(); } catch (Exception ignored) { }
        }
    }

    /**
     * Relays an HLS manifest and every resource referenced by it. Merely proxying the top-level
     * m3u8 is insufficient: relative child playlists resolve against localhost, while absolute
     * segments bypass provider Referer/Cookie headers. Rewriting all HLS URIs keeps the complete
     * request chain inside this relay, including encryption keys and initialization maps.
     */
    private void handleHlsAsset(HttpExchange exchange) throws IOException {
        String encoded = exchange.getRequestURI().getRawPath().substring("/hls/".length());
        try {
            encoded = stripRelayExtension(encoded);
            URI target = URI.create(new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8));
            if (!isHttp(target)) {
                sendFailure(exchange, 400, "Invalid HLS resource URL");
                return;
            }
            handleHlsTarget(exchange, target);
        } catch (IllegalArgumentException error) {
            sendFailure(exchange, 400, "Invalid HLS resource URL");
        }
    }

    private void handleHlsTarget(HttpExchange exchange, URI target) throws IOException {
        String method = exchange.getRequestMethod();
        if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }

        int requestNumber = requestCount.incrementAndGet();
        String range = first(exchange.getRequestHeaders(), "Range");
        try {
            boolean possibleDisguisedSegment = isPossibleDisguisedTransportStream(target);
            // Obfuscated provider segments have a PNG decoy prepended to the MPEG-TS payload.
            // Their downstream byte offsets no longer match the upstream resource, so fetch the
            // complete segment and expose a clean, ordinary transport stream to JavaFX.
            String upstreamRange = possibleDisguisedSegment ? "" : range;
            HttpRequest request = upstreamRequest(target, "GET", upstreamRange,
                    first(exchange.getRequestHeaders(), "If-Range"), Duration.ofSeconds(120));
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            String contentType = response.headers().firstValue("Content-Type").orElse(guessContentType(target));
            if (status != 200 && status != 206) {
                try (InputStream in = response.body()) { in.readNBytes(512); }
                sendFailure(exchange, 502, "Upstream HLS resource returned HTTP " + status);
                return;
            }

            if (isHlsManifest(target, contentType)) {
                byte[] source;
                try (InputStream in = response.body()) { source = in.readAllBytes(); }
                String rewritten = rewriteHlsManifest(new String(source, StandardCharsets.UTF_8), target);
                byte[] body = rewritten.getBytes(StandardCharsets.UTF_8);
                Headers headers = exchange.getResponseHeaders();
                headers.set("Content-Type", "application/vnd.apple.mpegurl; charset=utf-8");
                headers.set("Cache-Control", "no-store");
                headers.set("Content-Length", Integer.toString(body.length));
                System.out.println("[Aokuvue][MediaRelay] #" + requestNumber + " HLS manifest "
                        + safeHost(target) + " rewrittenBytes=" + body.length);
                if ("HEAD".equalsIgnoreCase(method)) exchange.sendResponseHeaders(200, -1);
                else {
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                }
                return;
            }

            if (possibleDisguisedSegment && !"HEAD".equalsIgnoreCase(method)) {
                try (InputStream in = new BufferedInputStream(response.body(), IO_BUFFER_BYTES)) {
                    byte[] prefix = in.readNBytes(DISGUISED_SEGMENT_PROBE_BYTES);
                    int transportOffset = transportStreamOffset(prefix);
                    if (transportOffset >= 0) {
                        long upstreamLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
                        long bodyLength = upstreamLength < 0 ? -1L : upstreamLength - transportOffset;
                        Headers headers = exchange.getResponseHeaders();
                        headers.set("Content-Type", "video/mp2t");
                        headers.set("Cache-Control", "no-store");
                        if (bodyLength >= 0) headers.set("Content-Length", Long.toString(bodyLength));
                        exchange.sendResponseHeaders(200, bodyLength >= 0 ? bodyLength : 0);
                        try (OutputStream out = new BufferedOutputStream(exchange.getResponseBody(), IO_BUFFER_BYTES)) {
                            out.write(prefix, transportOffset, prefix.length - transportOffset);
                            copyBuffered(in, out);
                            out.flush();
                        }
                        if (requestNumber <= 20) {
                            System.out.println("[Aokuvue][MediaRelay] #" + requestNumber
                                    + " HLS disguised segment strippedBytes=" + transportOffset
                                    + " host=" + safeHost(target));
                        }
                        return;
                    }

                    copyResponseHeaders(response, exchange.getResponseHeaders());
                    exchange.getResponseHeaders().set("Content-Type",
                            contentType == null || contentType.isBlank()
                                    ? "application/octet-stream" : contentType);
                    exchange.getResponseHeaders().set("Cache-Control", "no-store");
                    long bodyLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
                    exchange.sendResponseHeaders(status, bodyLength >= 0 ? bodyLength : 0);
                    try (OutputStream out = new BufferedOutputStream(exchange.getResponseBody(), IO_BUFFER_BYTES)) {
                        out.write(prefix);
                        copyBuffered(in, out);
                        out.flush();
                    }
                    return;
                }
            }

            copyResponseHeaders(response, exchange.getResponseHeaders());
            exchange.getResponseHeaders().set("Content-Type",
                    contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType);
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            long bodyLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            if (bodyLength < 0) bodyLength = rangeBodyLength(
                    response.headers().firstValue("Content-Range").orElse(""));
            if ("HEAD".equalsIgnoreCase(method)) {
                try (InputStream in = response.body()) { /* close upstream body */ }
                exchange.sendResponseHeaders(status, -1);
            } else {
                exchange.sendResponseHeaders(status, bodyLength >= 0 ? bodyLength : 0);
                try (InputStream in = new BufferedInputStream(response.body(), IO_BUFFER_BYTES);
                     OutputStream out = new BufferedOutputStream(exchange.getResponseBody(), IO_BUFFER_BYTES)) {
                    copyBuffered(in, out);
                    out.flush();
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            sendFailure(exchange, 502, "HLS relay interrupted");
        } catch (Exception error) {
            System.err.println("[Aokuvue][MediaRelay] HLS request failed: " + error);
            sendFailure(exchange, 502, "HLS relay failed: " + error.getMessage());
        } finally {
            try { exchange.close(); } catch (Exception ignored) { }
        }
    }

    private String rewriteHlsManifest(String manifest, URI manifestUri) {
        StringBuilder rewritten = new StringBuilder(manifest.length() + 256);
        String[] lines = manifest.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        Pattern uriAttribute = Pattern.compile("URI=\"([^\"]+)\"");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                line = relayHlsUri(manifestUri, trimmed);
            } else if (trimmed.startsWith("#")) {
                Matcher matcher = uriAttribute.matcher(line);
                StringBuffer output = new StringBuffer();
                while (matcher.find()) {
                    matcher.appendReplacement(output, Matcher.quoteReplacement(
                            "URI=\"" + relayHlsUri(manifestUri, matcher.group(1)) + "\""));
                }
                matcher.appendTail(output);
                line = output.toString();
            }
            rewritten.append(line);
            if (i < lines.length - 1) rewritten.append('\n');
        }
        return rewritten.toString();
    }

    private String relayHlsUri(URI base, String value) {
        URI absolute = base.resolve(value.trim());
        if (!isHttp(absolute)) return value;
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(absolute.toString().getBytes(StandardCharsets.UTF_8));
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/hls/" + encoded
                + relayExtension(absolute);
    }

    private static String stripRelayExtension(String encoded) {
        if (encoded == null) return "";
        for (String extension : List.of(".m3u8", ".ts", ".m4s", ".mp4", ".aac", ".key")) {
            if (encoded.endsWith(extension)) {
                return encoded.substring(0, encoded.length() - extension.length());
            }
        }
        return encoded;
    }

    private static String relayExtension(URI target) {
        String path = target == null || target.getPath() == null
                ? "" : target.getPath().toLowerCase(Locale.ROOT);
        if (path.endsWith(".m3u8")) return ".m3u8";
        if (path.endsWith(".ts") || path.endsWith(".image")
                || (path.endsWith(".png") && path.contains("/seg-"))) return ".ts";
        if (path.endsWith(".m4s")) return ".m4s";
        if (path.endsWith(".mp4")) return ".mp4";
        if (path.endsWith(".aac")) return ".aac";
        if (path.endsWith(".key")) return ".key";
        return "";
    }

    private static boolean isHttp(URI uri) {
        return uri != null && uri.isAbsolute() && uri.getHost() != null
                && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()));
    }

    private static boolean isHlsManifest(URI uri, String contentType) {
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        String path = uri == null || uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
        return type.contains("mpegurl") || type.contains("application/x-mpegurl") || path.endsWith(".m3u8");
    }

    private static String guessContentType(URI uri) {
        String path = uri == null || uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
        if (path.endsWith(".m3u8")) return "application/vnd.apple.mpegurl";
        if (path.endsWith(".ts") || path.endsWith(".image")
                || (path.endsWith(".png") && path.contains("/seg-"))) return "video/mp2t";
        if (path.endsWith(".m4s")) return "video/iso.segment";
        if (path.endsWith(".mp4")) return "video/mp4";
        if (path.endsWith(".aac")) return "audio/aac";
        return "application/octet-stream";
    }

    private static boolean isPossibleDisguisedTransportStream(URI uri) {
        String path = uri == null || uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
        return path.endsWith(".image") || (path.endsWith(".png") && path.contains("/seg-"));
    }

    static int transportStreamOffset(byte[] bytes) {
        if (bytes == null || bytes.length < TRANSPORT_STREAM_PACKET_BYTES * 3) return -1;
        int last = bytes.length - TRANSPORT_STREAM_PACKET_BYTES * 2;
        for (int offset = 0; offset < last; offset++) {
            if ((bytes[offset] & 0xff) == 0x47
                    && (bytes[offset + TRANSPORT_STREAM_PACKET_BYTES] & 0xff) == 0x47
                    && (bytes[offset + TRANSPORT_STREAM_PACKET_BYTES * 2] & 0xff) == 0x47) {
                return offset;
            }
        }
        return -1;
    }

    private boolean tryServePrefetchedRange(HttpExchange exchange, String downstreamRange, int requestNumber) throws IOException, InterruptedException {
        RangeSpec wanted = parseFixedRange(downstreamRange);
        if (wanted == null || !effectiveContentType().toLowerCase(Locale.ROOT).contains("video/mp4")) return false;
        long length = wanted.end() - wanted.start() + 1;
        if (length <= 0 || length > PREFETCH_BYTES) return false;

        CachedBlock block = cachedBlock;
        if (block == null || !block.contains(wanted.start(), wanted.end())) {
            ensureProbe();
            if (probeFailure != null) return false;
            long fetchEnd = wanted.start() + PREFETCH_BYTES - 1L;
            if (knownLength > 0) fetchEnd = Math.min(fetchEnd, knownLength - 1L);
            fetchEnd = Math.max(fetchEnd, wanted.end());
            HttpRequest request = upstreamRequest(
                    "GET", "bytes=" + wanted.start() + "-" + fetchEnd, null, Duration.ofSeconds(120));
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 206 || response.body() == null || response.body().length == 0) return false;
            String contentRange = response.headers().firstValue("Content-Range").orElse("");
            RangeSpec fetched = rangeFromContentRange(contentRange);
            if (fetched == null) return false;
            long total = totalFromContentRange(contentRange);
            if (total > 0) knownLength = total;
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            if (!contentType.isBlank()) observedContentType = contentType;
            block = new CachedBlock(fetched.start(), response.body(), total);
            cachedBlock = block;
            if (requestNumber <= 20) {
                System.out.println("[Aokuvue][MediaRelay] #" + requestNumber + " PREFETCH "
                        + fetched.start() + "-" + fetched.end() + " bytes=" + response.body().length);
            }
        }
        if (!block.contains(wanted.start(), wanted.end())) return false;

        int offset = Math.toIntExact(wanted.start() - block.start());
        int count = Math.toIntExact(wanted.end() - wanted.start() + 1);
        Headers headers = exchange.getResponseHeaders();
        applyStableResponseHeaders(headers);
        long total = block.totalLength() > 0 ? block.totalLength() : knownLength;
        headers.set("Content-Range", "bytes " + wanted.start() + "-" + wanted.end() + "/" + (total > 0 ? total : "*"));
        headers.set("Content-Length", Integer.toString(count));
        exchange.sendResponseHeaders(206, count);
        try (OutputStream out = new BufferedOutputStream(exchange.getResponseBody(), IO_BUFFER_BYTES)) {
            out.write(block.bytes(), offset, count);
            out.flush();
        }
        if (requestNumber <= 20) {
            System.out.println("[Aokuvue][MediaRelay] #" + requestNumber + " CACHE range=" + downstreamRange);
        }
        return true;
    }

    private static RangeSpec parseFixedRange(String value) {
        if (value == null) return null;
        Matcher matcher = FIXED_RANGE.matcher(value.trim());
        if (!matcher.matches()) return null;
        try {
            long start = Long.parseLong(matcher.group(1));
            long end = Long.parseLong(matcher.group(2));
            return end >= start ? new RangeSpec(start, end) : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static RangeSpec rangeFromContentRange(String value) {
        if (value == null) return null;
        Matcher matcher = CONTENT_RANGE.matcher(value.trim());
        if (!matcher.matches()) return null;
        try {
            long start = Long.parseLong(matcher.group(1));
            long end = Long.parseLong(matcher.group(2));
            return end >= start ? new RangeSpec(start, end) : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static void copyBuffered(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[IO_BUFFER_BYTES];
        int read;
        while ((read = in.read(buffer)) >= 0) {
            if (read > 0) out.write(buffer, 0, read);
        }
    }

    private HttpRequest upstreamRequest(String method, String range, String ifRange, Duration timeout) {
        return upstreamRequest(remoteUri, method, range, ifRange, timeout);
    }

    private HttpRequest upstreamRequest(URI target, String method, String range, String ifRange, Duration timeout) {
        HttpRequest.Builder request = HttpRequest.newBuilder(target)
                .timeout(timeout)
                .header("Accept-Encoding", "identity")
                .header("User-Agent", upstreamHeaders.getOrDefault("User-Agent", DEFAULT_USER_AGENT))
                .header("Accept", upstreamHeaders.getOrDefault("Accept", "*/*"));

        String referer = upstreamHeaders.getOrDefault("Referer", "");
        if (!referer.isBlank()) request.header("Referer", referer);

        // Do NOT invent an Origin header. Normal media element GETs commonly have Referer without
        // Origin, and some signed CDN endpoints are stricter when an unexpected Origin is supplied.
        upstreamHeaders.forEach((name, value) -> {
            if (name == null || value == null || value.isBlank()) return;
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.equals("host") || lower.equals("connection") || lower.equals("content-length")
                    || lower.equals("accept-encoding") || lower.equals("user-agent")
                    || lower.equals("accept") || lower.equals("referer") || lower.equals("origin")
                    || lower.equals("range") || lower.equals("if-range")) return;
            request.header(name, value);
        });

        if (range != null && !range.isBlank()) request.header("Range", range);
        if (ifRange != null && !ifRange.isBlank()) request.header("If-Range", ifRange);
        if ("HEAD".equalsIgnoreCase(method)) request.method("HEAD", HttpRequest.BodyPublishers.noBody());
        else request.GET();
        return request.build();
    }

    private void applyStableResponseHeaders(Headers headers) {
        headers.set("Content-Type", effectiveContentType());
        headers.set("Cache-Control", "no-store");
        if (rangeSupported) headers.set("Accept-Ranges", "bytes");
    }

    private String effectiveContentType() {
        if (!forcedContentType.isBlank()) return forcedContentType;
        if (!observedContentType.isBlank()) return observedContentType;
        return "application/octet-stream";
    }

    private static void copyResponseHeaders(HttpResponse<?> response, Headers target) {
        copy(response, target, "Content-Range");
        copy(response, target, "Accept-Ranges");
        copy(response, target, "ETag");
        copy(response, target, "Last-Modified");
        copy(response, target, "Expires");
        copy(response, target, "Content-Disposition");
    }

    private static void copy(HttpResponse<?> response, Headers target, String name) {
        List<String> values = response.headers().allValues(name);
        if (!values.isEmpty()) target.put(name, List.copyOf(values));
    }

    private static String first(Headers headers, String name) {
        String value = headers.getFirst(name);
        return value == null ? "" : value.trim();
    }

    private static long totalLength(HttpResponse<?> response, String contentRange) {
        long fromRange = totalFromContentRange(contentRange);
        if (fromRange >= 0) return fromRange;
        return response.headers().firstValueAsLong("Content-Length").orElse(-1L);
    }

    private static long totalFromContentRange(String contentRange) {
        if (contentRange == null) return -1L;
        Matcher matcher = CONTENT_RANGE.matcher(contentRange.trim());
        if (!matcher.matches() || "*".equals(matcher.group(3))) return -1L;
        try { return Long.parseLong(matcher.group(3)); }
        catch (NumberFormatException ignored) { return -1L; }
    }

    private static long rangeBodyLength(String contentRange) {
        if (contentRange == null) return -1L;
        Matcher matcher = CONTENT_RANGE.matcher(contentRange.trim());
        if (!matcher.matches()) return -1L;
        try {
            long start = Long.parseLong(matcher.group(1));
            long end = Long.parseLong(matcher.group(2));
            return end >= start ? end - start + 1 : -1L;
        } catch (NumberFormatException ignored) { return -1L; }
    }

    private static String contentTypeFor(String container, URI uri) {
        String c = container == null ? "" : container.toLowerCase(Locale.ROOT);
        String path = uri == null || uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
        String query = uri == null || uri.getRawQuery() == null ? "" : uri.getRawQuery().toLowerCase(Locale.ROOT);
        if (c.contains("hls") || path.endsWith(".m3u8")) return "application/vnd.apple.mpegurl";
        if (c.contains("mp4") || path.endsWith(".mp4") || query.contains("mime=video%2fmp4")
                || query.contains("mime=video/mp4") || query.contains("source=blogger")) return "video/mp4";
        return "";
    }

    private static String extensionFor(String container, URI uri) {
        String type = contentTypeFor(container, uri);
        if (type.contains("mpegurl")) return ".m3u8";
        if (type.equals("video/mp4")) return ".mp4";
        return ".bin";
    }

    private static String safeHost(URI uri) {
        return uri == null || uri.getHost() == null ? "<unknown>" : uri.getHost();
    }

    private static void sendFailure(HttpExchange exchange, int status, String message) throws IOException {
        if (exchange.getResponseCode() != -1) return;
        byte[] bytes = (message == null ? "Media relay failed" : message)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
    }

    @Override public void close() {
        try { server.stop(0); } catch (Exception ignored) { }
        executor.shutdownNow();
    }

    private record Probe(int statusCode, String contentType, long totalLength, boolean rangeSupported) {}
    private record RangeSpec(long start, long end) {}
    private record CachedBlock(long start, byte[] bytes, long totalLength) {
        long end() { return start + bytes.length - 1L; }
        boolean contains(long requestedStart, long requestedEnd) {
            return requestedStart >= start && requestedEnd <= end();
        }
    }
}
