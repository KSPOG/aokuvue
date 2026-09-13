package app.kspani.player;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class HttpMediaRelayHlsTest {
    @Test
    void rewritesNestedPlaylistsSegmentsKeysAndMapsThroughRelay() throws Exception {
        AtomicInteger authorizedRequests = new AtomicInteger();
        byte[] transportStream = new byte[188 * 3];
        transportStream[0] = 0x47;
        transportStream[188] = 0x47;
        transportStream[376] = 0x47;
        byte[] disguisedSegment = new byte[252 + transportStream.length];
        byte[] pngSignature = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        System.arraycopy(pngSignature, 0, disguisedSegment, 0, pngSignature.length);
        System.arraycopy(transportStream, 0, disguisedSegment, 252, transportStream.length);
        HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/master.m3u8", exchange -> sendAuthorized(exchange, authorizedRequests,
                "application/vnd.apple.mpegurl",
                "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nlow/playlist.m3u8\n"));
        upstream.createContext("/low/playlist.m3u8", exchange -> sendAuthorized(exchange, authorizedRequests,
                "application/vnd.apple.mpegurl",
                "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"../key.bin\"\n"
                        + "#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:5,\ndisguised.image?token=1\n"));
        upstream.createContext("/key.bin", exchange -> sendAuthorized(exchange, authorizedRequests,
                "application/octet-stream", "secret-key"));
        upstream.createContext("/low/init.mp4", exchange -> sendAuthorized(exchange, authorizedRequests,
                "video/mp4", "init-data"));
        upstream.createContext("/low/disguised.image", exchange -> sendAuthorized(exchange, authorizedRequests,
                "image/png", disguisedSegment));
        upstream.start();

        URI remote = URI.create("http://127.0.0.1:" + upstream.getAddress().getPort() + "/master.m3u8");
        try (HttpMediaRelay relay = HttpMediaRelay.start(remote, Map.of(
                "Referer", "https://provider.example/watch/1",
                "Origin", "https://provider.example",
                "Cookie", "session=valid",
                "User-Agent", "ProviderBrowser"
        ), "hls")) {
            HttpClient client = HttpClient.newHttpClient();
            String master = get(client, relay.localUri());
            URI childPlaylist = URI.create(firstMediaLine(master));
            assertEquals("127.0.0.1", childPlaylist.getHost());
            assertFalse(master.contains("low/playlist.m3u8"));

            String child = get(client, childPlaylist);
            URI key = URI.create(attributeUri(child, "#EXT-X-KEY"));
            URI init = URI.create(attributeUri(child, "#EXT-X-MAP"));
            URI segment = URI.create(firstMediaLine(child));
            assertTrue(childPlaylist.getPath().endsWith(".m3u8"));
            assertTrue(segment.getPath().endsWith(".ts"));
            assertEquals("secret-key", get(client, key));
            assertEquals("init-data", get(client, init));
            HttpResponse<byte[]> segmentResponse = client.send(
                    HttpRequest.newBuilder(segment).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, segmentResponse.statusCode());
            assertEquals("video/mp2t", segmentResponse.headers().firstValue("Content-Type").orElseThrow());
            assertArrayEquals(transportStream, segmentResponse.body());
            assertTrue(authorizedRequests.get() >= 5);
        } finally {
            upstream.stop(0);
        }
    }

    private static String get(HttpClient client, URI uri) throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(uri).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, response.statusCode(), "GET " + uri);
        return response.body();
    }

    private static String firstMediaLine(String playlist) {
        return playlist.lines().map(String::trim)
                .filter(line -> !line.isBlank() && !line.startsWith("#"))
                .findFirst().orElseThrow();
    }

    private static String attributeUri(String playlist, String tag) {
        String line = playlist.lines().filter(value -> value.startsWith(tag)).findFirst().orElseThrow();
        int start = line.indexOf("URI=\"") + 5;
        int end = line.indexOf('"', start);
        return line.substring(start, end);
    }

    private static void sendAuthorized(
            HttpExchange exchange,
            AtomicInteger authorizedRequests,
            String contentType,
            String body
    ) throws IOException {
        sendAuthorized(exchange, authorizedRequests, contentType, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void sendAuthorized(
            HttpExchange exchange,
            AtomicInteger authorizedRequests,
            String contentType,
            byte[] bytes
    ) throws IOException {
        boolean authorized = "https://provider.example/watch/1".equals(exchange.getRequestHeaders().getFirst("Referer"))
                && "https://provider.example".equals(exchange.getRequestHeaders().getFirst("Origin"))
                && "session=valid".equals(exchange.getRequestHeaders().getFirst("Cookie"))
                && "ProviderBrowser".equals(exchange.getRequestHeaders().getFirst("User-Agent"));
        if (!authorized) {
            exchange.sendResponseHeaders(403, -1);
            exchange.close();
            return;
        }
        authorizedRequests.incrementAndGet();
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
