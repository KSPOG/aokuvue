package app.kspani.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class JsonHttpClient {
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public ObjectMapper mapper() { return mapper; }

    public CompletableFuture<JsonNode> get(URI uri, Map<String, String> headers) {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .GET()
                .header("Accept", "application/json")
                .header("User-Agent", "Aokuvue/1.5.14");
        headers.forEach(b::header);
        return client.sendAsync(b.build(), HttpResponse.BodyHandlers.ofString())
                .thenApply(this::parseResponse);
    }

    public CompletableFuture<JsonNode> postJson(URI uri, JsonNode body, Map<String, String> headers) {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("User-Agent", "Aokuvue/1.5.14");
        headers.forEach(b::header);
        return sendJsonWithRetry(b.build(), 0);
    }

    private CompletableFuture<JsonNode> sendJsonWithRetry(HttpRequest request, int attempt) {
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenCompose(response -> {
            String body = response.body() == null ? "" : response.body().stripLeading();
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            boolean json = contentType.toLowerCase().contains("json") || body.startsWith("{") || body.startsWith("[");
            boolean transientFailure = response.statusCode() == 429 || response.statusCode() >= 500 || !json;
            if (transientFailure && attempt < 2) {
                return CompletableFuture.supplyAsync(() -> request,
                                CompletableFuture.delayedExecutor(500L * (attempt + 1), TimeUnit.MILLISECONDS))
                        .thenCompose(next -> sendJsonWithRetry(next, attempt + 1));
            }
            return CompletableFuture.completedFuture(parseResponse(response));
        });
    }


    public CompletableFuture<String> getText(URI uri, Map<String, String> headers) {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .GET()
                .header("Accept", "text/plain,text/vtt,application/x-subrip,*/*")
                .header("User-Agent", "Aokuvue/1.5.14");
        headers.forEach(b::header);
        return client.sendAsync(b.build(), HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        throw new IllegalStateException("HTTP " + response.statusCode() + " while loading text resource.");
                    }
                    return response.body();
                });
    }

    public CompletableFuture<String> getHtml(URI uri, Map<String, String> headers) {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(15))
                .GET()
                .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36 Aokuvue/1.5.14");
        headers.forEach(b::header);
        return client.sendAsync(b.build(), HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        throw new IllegalStateException("HTTP " + response.statusCode() + " while loading web source.");
                    }
                    return response.body();
                });
    }

    private JsonNode parseResponse(HttpResponse<String> response) {
        try {
            String body = response.body() == null ? "" : response.body().stripLeading();
            String contentType = response.headers().firstValue("Content-Type").orElse("unknown");
            if (!(body.startsWith("{") || body.startsWith("["))) {
                throw new IllegalStateException("Expected JSON but received " + contentType + " (HTTP " + response.statusCode() + ").");
            }
            JsonNode node = mapper.readTree(response.body());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("HTTP " + response.statusCode() + ": " + node);
            }
            return node;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Invalid JSON response", e);
        }
    }
}
