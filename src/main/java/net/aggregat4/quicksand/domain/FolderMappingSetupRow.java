package net.aggregat4.quicksand.domain;

import java.util.List;
import java.util.Set;

public record FolderMappingSetupRow(
    FolderSpecialUse specialUse,
    AccountFolderMapping mapping,
    List<NamedFolder> folders,
    Set<Integer> folderIdsMappedToOtherRoles) {

  public FolderMappingSetupRow(
      FolderSpecialUse specialUse, AccountFolderMapping mapping, List<NamedFolder> folders) {
    this(specialUse, mapping, folders, Set.of());
  }

  public boolean configured() {
    return mapping != null && mapping.configured();
  }

  public String label() {
    return switch (specialUse) {
      case ARCHIVE -> "Archive";
      case TRASH -> "Trash";
      case JUNK -> "Junk/Spam";
      case SENT -> "Sent";
      case DRAFTS -> "Drafts";
      case INBOX -> "Inbox";
    };
  }

  public String suggestedRemoteName() {
    return switch (specialUse) {
      case ARCHIVE -> "Archive";
      case TRASH -> "Trash";
      case JUNK -> "Junk";
      case SENT -> "Sent";
      case DRAFTS -> "Drafts";
      case INBOX -> "INBOX";
    };
  }

  public List<NamedFolder> availableFolders() {
    return folders.stream()
        .filter(folder -> folder.specialUse() != FolderSpecialUse.INBOX)
        .toList();
  }

  public List<NamedFolder> candidateFolders() {
    return folders.stream().filter(folder -> folder.specialUse() == specialUse).toList();
  }

  public List<NamedFolder> otherFolders() {
    return folders.stream()
        .filter(folder -> folder.specialUse() != FolderSpecialUse.INBOX)
        .filter(folder -> folder.specialUse() != specialUse)
        .filter(folder -> folder.specialUse() == null)
        .filter(folder -> !folderIdsMappedToOtherRoles.contains(folder.id()))
        .toList();
  }

  public boolean needsConfirmation() {
    return mapping != null && mapping.status() == FolderMappingStatus.AUTO_DETECTED;
  }

  public boolean hasConflict() {
    return mapping != null && mapping.status() == FolderMappingStatus.CONFLICT;
  }

  public boolean isMissing() {
    return mapping == null || mapping.status() == FolderMappingStatus.MISSING;
  }

  public String folderOptionLabel(NamedFolder folder) {
    String remote =
        folder.remoteName() == null || folder.remoteName().isBlank()
            ? folder.name()
            : folder.remoteName();
    if (folder.specialUse() == null) {
      return remote;
    }
    return "%s (%s)".formatted(remote, folder.specialUse().name());
  }

  public String statusLabel() {
    if (mapping == null) {
      return "Missing";
    }
    return switch (mapping.status()) {
      case AUTO_DETECTED -> "Auto-detected";
      case USER_CONFIRMED -> "Configured";
      case MISSING -> "Missing";
      case CONFLICT -> "Conflict";
    };
  }
}
