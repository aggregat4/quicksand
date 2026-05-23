package net.aggregat4.quicksand.imap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.aggregat4.quicksand.domain.FolderMappingStatus;
import net.aggregat4.quicksand.domain.FolderSpecialUse;
import net.aggregat4.quicksand.domain.NamedFolder;
import org.junit.jupiter.api.Test;

class FolderRemoteNameMatcherTest {

  @Test
  void matchesExactRemoteNames() {
    NamedFolder folder =
        new NamedFolder(
            1,
            "Archive",
            0,
            "INBOX.Archive",
            FolderSpecialUse.ARCHIVE,
            1L,
            true,
            FolderMappingStatus.MISSING,
            null,
            null);

    assertTrue(FolderRemoteNameMatcher.matches(folder, "INBOX.Archive"));
    assertTrue(FolderRemoteNameMatcher.matchesStoredRemoteName("Archive", folder));
    assertTrue(FolderRemoteNameMatcher.matchesStoredRemoteName("INBOX.Archive", folder));
    assertFalse(FolderRemoteNameMatcher.matches(folder, "INBOX.Trash"));
  }
}
