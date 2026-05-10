package net.aggregat4.quicksand.domain;

import java.util.Objects;

public record NamedFolder(
    int id,
    String name,
    long lastSeenUid,
    String remoteName,
    FolderSpecialUse specialUse,
    Long uidValidity,
    boolean syncEnabled,
    FolderMappingStatus mappingStatus)
    implements Folder {

  public NamedFolder(int id, String name, long lastSeenUid) {
    this(id, name, lastSeenUid, null, null, null, true, FolderMappingStatus.MISSING);
  }

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
