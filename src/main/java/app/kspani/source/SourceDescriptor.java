package app.kspani.source;

public record SourceDescriptor(
        String id,
        String name,
        String language,
        SourceCapabilities capabilities,
        int priority
) {
    @Override
    public String toString() {
        return name;
    }
}
