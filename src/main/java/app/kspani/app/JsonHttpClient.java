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
    private static final int MAX_RETRIES = 2;
    private static final String APP_USER_AGENT = "Aokuvue/1.5.22";

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
                .header("User-Agent", APP_USER_AGENT);
        applyOverrides(b, headers);
        return sendJsonWithRetry(b.build(), 0);
    }

    public CompletableFuture<JsonNode> postJson(URI uri, JsonNode body, Map<String, String> headers) {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("User-Agent", APP_USER_AGENT);
        applyOverrides(b, headers);
        return sendJsonWithRetry(b.build(), 0);
    }

    private CompletableFuture<JsonNode> sendJsonWithRetry(HttpRequest request, int attempt) {
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenCompose(response -> {
            String body = response.body() == null ? "" : response.body().stripLeading();
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            boolean json = contentType.toLowerCase().contains("json")
                    || body.startsWith("{")
                    || body.startsWith("[");
            boolean transientFailure = shouldRetry(response.statusCode()) || !json;
            if (transientFailure && attempt < MAX_RETRIES) {
                return delayed(request, attempt).thenCompose(next -> sendJsonWithRetry(next, attempt + 1));
            }
            return CompletableFuture.completedFuture(parseResponse(response));
        });
    }

    public CompletableFuture<String> getText(URI uri, Map<String, String> headers) {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .GET()
                .header("Accept", "text/plain,text/vtt,application/x-subrip,*/*")
                .header("User-Agent", APP_USER_AGENT);
        applyOverrides(b, headers);
        return sendTextWithRetry(b.build(), 0, "text resource");
    }

    public CompletableFuture<String> getHtml(URI uri, Map<String, String> headers) {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(15))
                .GET()
                .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                        + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36 "
                        + APP_USER_AGENT);
        applyOverrides(b, headers);
        return sendTextWithRetry(b.build(), 0, "web source");
    }

    private static void applyOverrides(HttpRequest.Builder builder, Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) return;
        headers.forEach((name, value) -> {
            if (name == null || name.isBlank() || value == null || value.isBlank()) return;
            // Provider requests sometimes require their browser User-Agent, Accept, Referer or
            // Origin exactly. setHeader replaces our application default rather than producing
            // duplicate values such as two User-Agent headers.
            builder.setHeader(name, value);
        });
    }

    private CompletableFuture<String> sendTextWithRetry(
            HttpRequest request,
            int attempt,
            String resourceName
    ) {
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenCompose(response -> {
            String body = response.body() == null ? "" : response.body();
            boolean transientFailure = shouldRetry(response.statusCode()) || body.isBlank();
            if (transientFailure && attempt < MAX_RETRIES) {
                return delayed(request, attempt)
                        .thenCompose(next -> sendTextWithRetry(next, attempt + 1, resourceName));
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "HTTP " + response.statusCode() + " while loading " + resourceName + "."));
            }
            if (body.isBlank()) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "Empty response while loading " + resourceName + "."));
            }
            return CompletableFuture.completedFuture(body);
        });
    }

    private static boolean shouldRetry(int statusCode) {
        return statusCode == 408 || statusCode == 425 || statusCode == 429 || statusCode >= 500;
    }

    private static CompletableFuture<HttpRequest> delayed(HttpRequest request, int attempt) {
        return CompletableFuture.supplyAsync(
                () -> request,
                CompletableFuture.delayedExecutor(500L * (attempt + 1), TimeUnit.MILLISECONDS));
    }

    private JsonNode parseResponse(HttpResponse<String> response) {
        try {
            String body = response.body() == null ? "" : response.body().stripLeading();
            String contentType = response.headers().firstValue("Content-Type").orElse("unknown");
            if (!(body.startsWith("{") || body.startsWith("["))) {
                throw new IllegalStateException(
                        "Expected JSON but received " + contentType
                                + " (HTTP " + response.statusCode() + ").");
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
