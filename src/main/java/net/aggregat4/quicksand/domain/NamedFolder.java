package net.aggregat4.quicksand.domain;

public record NamedFolder(int id, String name, long lastSeenUid) implements Folder {
}
