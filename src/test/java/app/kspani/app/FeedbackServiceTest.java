package app.kspani.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeedbackServiceTest {
    @Test
    void normalizesTitleForDiscordThreadName() {
        assertEquals("Player freezes on seek", FeedbackService.threadName("  Player   freezes on seek  "));
    }

    @Test
    void limitsDiscordThreadTitleToOneHundredCharacters() {
        String title = FeedbackService.normalizeTitle("x".repeat(140));
        assertEquals(FeedbackService.MAX_TITLE_LENGTH, title.length());
    }

    @Test
    void includesEscapedTitleAndMessageInSuggestionPayload() {
        String content = FeedbackService.content(
                FeedbackService.Kind.SUGGESTION,
                "Add *profiles*",
                "Please support separate lists."
        );
        assertTrue(content.contains("**AOKUVUE Suggestion**"));
        assertTrue(content.contains("**Title:** Add \\*profiles\\*"));
        assertTrue(content.endsWith("Please support separate lists."));
    }

    @Test
    void labelsBugReportPayload() {
        String content = FeedbackService.content(
                FeedbackService.Kind.BUG_REPORT,
                "Playback failure",
                "The player stays black."
        );
        assertTrue(content.startsWith("**AOKUVUE Bug Report**"));
        assertTrue(content.contains("**Title:** Playback failure"));
    }

    @Test
    void rejectsMissingTitleBeforeSendingWebhookRequest() {
        FeedbackService service = new FeedbackService(new ObjectMapper());
        CompletionException error = assertThrows(CompletionException.class, () ->
                service.submit(FeedbackService.Kind.BUG_REPORT, "  ", "Player stays black").join());
        assertEquals("Enter a title before sending.", error.getCause().getMessage());
    }
}
