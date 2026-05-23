package net.aggregat4.quicksand.domain;

import java.util.Objects;

/** Folder metadata for mailbox navigation. Equality is by database id only. */
public record NamedFolder(
    int id,
    String name,
    long lastSeenUid,
    String remoteName,
    FolderSpecialUse specialUse,
    Long uidValidity,
    boolean syncEnabled,
    FolderMappingStatus mappingStatus,
    Long highestModSeq,
    Long lastFullSyncEpochS)
    implements Folder {

  public NamedFolder(int id, String name, long lastSeenUid) {
    this(id, name, lastSeenUid, null, null, null, true, FolderMappingStatus.MISSING, null, null);
  }

  public NamedFolder withSyncCheckpoint(Long highestModSeq, Long lastFullSyncEpochS) {
    return new NamedFolder(
        id,
        name,
        lastSeenUid,
        remoteName,
        specialUse,
        uidValidity,
        syncEnabled,
        mappingStatus,
        highestModSeq,
        lastFullSyncEpochS);
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
