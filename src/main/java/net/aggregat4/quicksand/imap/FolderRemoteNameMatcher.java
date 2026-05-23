package net.aggregat4.quicksand.imap;

import net.aggregat4.quicksand.domain.NamedFolder;

public final class FolderRemoteNameMatcher {
  private FolderRemoteNameMatcher() {}

  public static boolean matches(NamedFolder localFolder, String remoteName) {
    if (remoteName == null || remoteName.isBlank()) {
      return false;
    }
    return matchesRemoteName(localFolder.remoteName(), remoteName)
        || matchesRemoteName(localFolder.name(), remoteName);
  }

  public static boolean matchesStoredRemoteName(String storedRemoteName, NamedFolder folder) {
    if (storedRemoteName == null || storedRemoteName.isBlank()) {
      return false;
    }
    return matchesRemoteName(folder.remoteName(), storedRemoteName)
        || matchesRemoteName(folder.name(), storedRemoteName);
  }

  private static boolean matchesRemoteName(String left, String right) {
    if (left == null || right == null) {
      return false;
    }
    if (left.equals(right)) {
      return true;
    }
    return left.endsWith("/" + right)
        || left.endsWith("." + right)
        || right.endsWith("/" + left)
        || right.endsWith("." + left);
  }
}
