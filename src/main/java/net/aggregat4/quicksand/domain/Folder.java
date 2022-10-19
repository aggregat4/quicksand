package net.aggregat4.quicksand.domain;

public sealed interface Folder permits NamedFolder, SearchFolder {
    String name();
}
