package net.aggregat4.quicksand.domain;

public record SearchFolder(Query query) implements Folder {
    public String name() {
        return query.query();
    }
}
