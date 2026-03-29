package net.aggregat4.quicksand.domain;

public sealed interface Folder permits DraftsFolder, NamedFolder, SearchFolder {
    String name();
}
