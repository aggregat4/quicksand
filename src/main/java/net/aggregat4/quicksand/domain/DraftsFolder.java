package net.aggregat4.quicksand.domain;

public record DraftsFolder() implements Folder {
    @Override
    public String name() {
        return "Drafts";
    }
}
