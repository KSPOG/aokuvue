package app.kspani.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.regex.Pattern;

/** Keeps a small, redacted copy of console output for user-submitted bug reports. */
public final class DiagnosticLog {
    private static final int MAX_LINES = 400;
    private static final Deque<String> LINES = new ArrayDeque<>();
    private static final Pattern URL = Pattern.compile("(?i)https?://\\S+");
    private static final Pattern SECRET = Pattern.compile("(?i)(authorization|token|secret|webhook|cookie)(\\s*[:=]\\s*)\\S+");
    private static boolean installed;

    private DiagnosticLog() {}

    public static synchronized void installSystemCapture() {
        if (installed) return;
        installed = true;
        System.setOut(new PrintStream(new Tee(System.out, "OUT"), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new Tee(System.err, "ERR"), true, StandardCharsets.UTF_8));
    }

    public static synchronized String recentText(int maxCharacters) {
        StringBuilder value = new StringBuilder();
        value.append("Captured: ").append(Instant.now()).append('\n');
        value.append("OS: ").append(System.getProperty("os.name", "unknown")).append(' ')
                .append(System.getProperty("os.version", "unknown")).append('\n');
        value.append("Java: ").append(System.getProperty("java.version", "unknown")).append("\n\n");
        for (String line : LINES) value.append(line).append('\n');
        if (value.length() <= maxCharacters) return value.toString();
        return "[older entries omitted]\n" + value.substring(value.length() - maxCharacters);
    }

    private static synchronized void append(String stream, String line) {
        if (line == null || line.isBlank()) return;
        String redacted = URL.matcher(line).replaceAll("[redacted-url]");
        redacted = SECRET.matcher(redacted).replaceAll("$1$2[redacted]");
        LINES.addLast(Instant.now() + " [" + stream + "] " + redacted);
        while (LINES.size() > MAX_LINES) LINES.removeFirst();
    }

    private static final class Tee extends OutputStream {
        private final PrintStream delegate;
        private final String stream;
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();

        private Tee(PrintStream delegate, String stream) {
            this.delegate = delegate;
            this.stream = stream;
        }

        @Override public synchronized void write(int value) {
            delegate.write(value);
            capture(value);
        }

        @Override public synchronized void write(byte[] bytes, int offset, int length) {
            delegate.write(bytes, offset, length);
            for (int i = offset; i < offset + length; i++) capture(bytes[i]);
        }

        private void capture(int value) {
            if (value == '\n') {
                append(stream, line.toString(StandardCharsets.UTF_8).stripTrailing());
                line.reset();
            } else if (value != '\r' && line.size() < 16_384) {
                line.write(value);
            }
        }

        @Override public void flush() throws IOException { delegate.flush(); }
    }
}
