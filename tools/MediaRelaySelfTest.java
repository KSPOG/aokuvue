import app.kspani.player.HttpMediaRelay;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** JDK-only smoke test for the localhost media relay. */
public final class MediaRelaySelfTest {
    public static void main(String[] args) throws Exception {
        byte[] data = "0123456789abcdefghijklmnopqrstuvwxyz".getBytes(StandardCharsets.UTF_8);
        AtomicInteger upstreamGets = new AtomicInteger();
        HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/video", exchange -> {
            String referer = exchange.getRequestHeaders().getFirst("Referer");
            String userAgent = exchange.getRequestHeaders().getFirst("User-Agent");
            String origin = exchange.getRequestHeaders().getFirst("Origin");
            if (!"https://player.example/embed/1".equals(referer) || !"ExactBrowserUA".equals(userAgent) || origin != null) {
                exchange.sendResponseHeaders(403, -1);
                exchange.close();
                return;
            }
            upstreamGets.incrementAndGet();
            int start = 0;
            int end = data.length - 1;
            int status = 200;
            String range = exchange.getRequestHeaders().getFirst("Range");
            if (range != null && range.startsWith("bytes=")) {
                String[] parts = range.substring(6).split("-", 2);
                start = Integer.parseInt(parts[0]);
                if (parts.length > 1 && !parts[1].isBlank()) end = Integer.parseInt(parts[1]);
                status = 206;
                exchange.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + data.length);
            }
            exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
            exchange.getResponseHeaders().set("Content-Type", "video/mp4");
            int length = end - start + 1;
            exchange.sendResponseHeaders(status, length);
            exchange.getResponseBody().write(data, start, length);
            exchange.close();
        });
        upstream.start();

        URI remote = URI.create("http://127.0.0.1:" + upstream.getAddress().getPort() + "/video");
        try (HttpMediaRelay relay = HttpMediaRelay.start(remote, Map.of(
                "Referer", "https://player.example/embed/1",
                "User-Agent", "ExactBrowserUA"
        ), "mp4")) {
            if (!relay.localUri().getPath().endsWith(".mp4")) {
                throw new AssertionError("Relay should expose an .mp4 path: " + relay.localUri());
            }

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest head = HttpRequest.newBuilder(relay.localUri())
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<Void> headResponse = client.send(head, HttpResponse.BodyHandlers.discarding());
            if (headResponse.statusCode() != 200) throw new AssertionError("HEAD expected 200, got " + headResponse.statusCode());
            if (!"video/mp4".equalsIgnoreCase(headResponse.headers().firstValue("Content-Type").orElse(""))) {
                throw new AssertionError("HEAD content type missing/incorrect");
            }
            if (!Integer.toString(data.length).equals(headResponse.headers().firstValue("Content-Length").orElse(""))) {
                throw new AssertionError("HEAD content length mismatch: " + headResponse.headers().firstValue("Content-Length").orElse(""));
            }

            HttpRequest request = HttpRequest.newBuilder(relay.localUri())
                    .header("Range", "bytes=5-9")
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 206) throw new AssertionError("Expected 206, got " + response.statusCode());
            if (!"56789".equals(response.body())) throw new AssertionError("Range body mismatch: " + response.body());
            if (!"bytes 5-9/36".equals(response.headers().firstValue("Content-Range").orElse(""))) {
                throw new AssertionError("Content-Range was not preserved");
            }
            int beforeCached = upstreamGets.get();
            HttpRequest cachedRequest = HttpRequest.newBuilder(relay.localUri())
                    .header("Range", "bytes=10-14")
                    .GET()
                    .build();
            HttpResponse<String> cachedResponse = client.send(cachedRequest, HttpResponse.BodyHandlers.ofString());
            if (cachedResponse.statusCode() != 206 || !"abcde".equals(cachedResponse.body())) {
                throw new AssertionError("Prefetch cache body mismatch: " + cachedResponse.body());
            }
            if (upstreamGets.get() != beforeCached) {
                throw new AssertionError("Second nearby range should have been served from relay cache");
            }
            System.out.println("KSP Ani media relay self-test: PASS");
        } finally {
            upstream.stop(0);
        }
    }
}
