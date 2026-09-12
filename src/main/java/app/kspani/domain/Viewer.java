package app.kspani.domain;

import java.net.URI;

public record Viewer(int id, String name, String avatarUrl, URI profileUri) {}
