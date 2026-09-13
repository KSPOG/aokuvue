package app.kspani.app;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class FeedbackService {
    public enum Kind { SUGGESTION, BUG_REPORT }

    private static final URI SUGGESTION_WEBHOOK = URI.create("https://discord.com/api/webhooks/1548384783141306388/Y9VmeGRsVoNo9gSo1-4rQrNAV9Mi5TyiEqq488IAgJYQrQhwCh5KU87mKj2UOc27wXC_");
    private static final URI BUG_WEBHOOK = URI.create("https://discord.com/api/webhooks/1548385202458460223/Idn4PTgve35TvcjLeB_YFNeNKdTp3A8b8q5X2FHrepSTXX23_-TsY9ivRq-gxDfaaAyn");
    private static final int MAX_MESSAGE_LENGTH = 1_700;
    private static final int MAX_LOG_LENGTH = 60_000;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(12)).build();
    private final ObjectMapper mapper;

    public FeedbackService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public CompletableFuture<Void> submit(Kind kind, String text) {
        String message = normalize(text);
        if (message.isBlank()) return CompletableFuture.failedFuture(new IllegalArgumentException("Enter a message before sending."));
        try {
            return kind == Kind.BUG_REPORT ? sendBug(message) : sendSuggestion(message);
        } catch (IOException error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    private CompletableFuture<Void> sendSuggestion(String message) throws IOException {
        byte[] json = mapper.writeValueAsBytes(payload("**AOKUVUE Suggestion**\n" + message, threadName(Kind.SUGGESTION), false));
        HttpRequest request = HttpRequest.newBuilder(SUGGESTION_WEBHOOK)
                .timeout(Duration.ofSeconds(25)).header("Content-Type", "application/json")
                .header("User-Agent", "AOKUVUE-Feedback/1.0")
                .POST(HttpRequest.BodyPublishers.ofByteArray(json)).build();
        return send(request);
    }

    private CompletableFuture<Void> sendBug(String message) throws IOException {
        String boundary = "AokuvueBoundary" + UUID.randomUUID().toString().replace("-", "");
        byte[] payload = mapper.writeValueAsBytes(payload("**AOKUVUE Bug Report**\n" + message, threadName(Kind.BUG_REPORT), true));
        byte[] logs = DiagnosticLog.recentText(MAX_LOG_LENGTH).getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        part(body, boundary, "payload_json", null, "application/json", payload);
        part(body, boundary, "files[0]", "aokuvue-diagnostics.txt", "text/plain; charset=utf-8", logs);
        body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(BUG_WEBHOOK)
                .timeout(Duration.ofSeconds(25)).header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("User-Agent", "AOKUVUE-Feedback/1.0")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
        return send(request);
    }

    private CompletableFuture<Void> send(HttpRequest request) {
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenCompose(response -> response.statusCode() >= 200 && response.statusCode() < 300
                        ? CompletableFuture.completedFuture(null)
                        : CompletableFuture.failedFuture(new IOException(discordError(response))));
    }

    private Map<String, Object> payload(String content, String threadName, boolean includesLog) {
        Map<String,Object> value=new LinkedHashMap<>();
        value.put("content",content);
        value.put("thread_name",threadName);
        value.put("allowed_mentions",Map.of("parse",List.of()));
        if(includesLog)value.put("attachments",List.of(Map.of("id",0,"filename","aokuvue-diagnostics.txt","description","Redacted AOKUVUE runtime diagnostics")));
        return value;
    }

    private static String threadName(Kind kind) {
        String type=kind==Kind.BUG_REPORT?"Bug Report":"Suggestion";
        return "AOKUVUE " + type + " - " + DateTimeFormatter.ofPattern("uuuu-MM-dd HH-mm 'UTC'").format(ZonedDateTime.now(ZoneOffset.UTC));
    }

    private static String discordError(HttpResponse<String> response) {
        String detail=response.body()==null?"":response.body().strip().replaceAll("\\s+"," ");
        if(detail.length()>500)detail=detail.substring(0,500)+"…";
        return "Discord returned HTTP "+response.statusCode()+(detail.isBlank()?"":" · "+detail);
    }

    private static String normalize(String text) {
        String value = text == null ? "" : text.strip();
        return value.length() <= MAX_MESSAGE_LENGTH ? value : value.substring(0, MAX_MESSAGE_LENGTH);
    }

    private static void part(ByteArrayOutputStream body, String boundary, String name, String filename, String contentType, byte[] data) throws IOException {
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"" +
                (filename == null ? "" : "; filename=\"" + filename + "\"") + "\r\nContent-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(data);
        body.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }
}
