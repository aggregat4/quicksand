package net.aggregat4.quicksand.domain;

import java.util.List;

public record FolderMappingSetupRow(
    FolderSpecialUse specialUse, AccountFolderMapping mapping, List<NamedFolder> folders) {

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
