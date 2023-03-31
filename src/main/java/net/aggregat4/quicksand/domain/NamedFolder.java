package net.aggregat4.quicksand.domain;

import java.util.Objects;

public record NamedFolder(int id, String name, long lastSeenUid) implements Folder {

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof NamedFolder that)) return false;
        return id == that.id;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
