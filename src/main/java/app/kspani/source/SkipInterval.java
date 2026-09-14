package app.kspani.source;

/** Provider-supplied opening/ending interval, stored in milliseconds. */
public record SkipInterval(long startMs, long endMs) {
    public SkipInterval { startMs = Math.max(0, startMs); endMs = Math.max(startMs, endMs); }
    public boolean valid() { return endMs > startMs; }
    public boolean contains(double positionMs) { return valid() && positionMs >= startMs && positionMs < endMs; }
}
