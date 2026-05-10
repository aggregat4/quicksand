package net.aggregat4.quicksand.domain;

public record AccountFolderMapping(
    int id,
    int accountId,
    FolderSpecialUse specialUse,
    Integer folderId,
    String remoteName,
    FolderMappingStatus status) {

  public boolean configured() {
    return folderId != null
        && remoteName != null
        && (status == FolderMappingStatus.AUTO_DETECTED
            || status == FolderMappingStatus.USER_CONFIRMED);
  }
}
