package net.aggregat4.quicksand.repository;

import static net.aggregat4.quicksand.repository.DatabaseMaintenance.migrateDb;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import javax.sql.DataSource;
import net.aggregat4.quicksand.DbTestUtils;
import net.aggregat4.quicksand.domain.Account;
import net.aggregat4.quicksand.domain.FolderMappingStatus;
import net.aggregat4.quicksand.domain.FolderSpecialUse;
import net.aggregat4.quicksand.domain.NamedFolder;
import org.junit.jupiter.api.Test;

class DbFolderRepositoryTest {

  @Test
  void storesRemoteFolderMetadata() throws Exception {
    DataSource ds = DbTestUtils.getTempSqlite();
    migrateDb(ds);
    DbAccountRepository accountRepository = new DbAccountRepository(ds);
    DbFolderRepository folderRepository = new DbFolderRepository(ds);
    accountRepository.createAccountIfNew(
        new Account(
            -1,
            "Folder Metadata Account",
            "imap.example.test",
            993,
            "user",
            "secret",
            "smtp.example.test",
            587,
            "user",
            "secret"));
    Account account = accountRepository.getAccounts().getFirst();

    NamedFolder folder =
        folderRepository.createFolder(
            account, "Inbox", "INBOX", FolderSpecialUse.INBOX, 123456789L);

    NamedFolder stored = folderRepository.getFolder(folder.id());
    assertEquals("INBOX", stored.remoteName());
    assertEquals(FolderSpecialUse.INBOX, stored.specialUse());
    assertEquals(123456789L, stored.uidValidity());
    assertTrue(stored.syncEnabled());
    assertEquals(FolderMappingStatus.MISSING, stored.mappingStatus());

    folderRepository.updateRemoteMetadata(stored, "Archive", FolderSpecialUse.ARCHIVE, 987654321L);
    NamedFolder updated = folderRepository.getFolders(account.id()).getFirst();
    assertEquals("Archive", updated.remoteName());
    assertEquals(FolderSpecialUse.ARCHIVE, updated.specialUse());
    assertEquals(987654321L, updated.uidValidity());
  }

  @Test
  void storesCondstoreSyncCheckpoint() throws Exception {
    DataSource ds = DbTestUtils.getTempSqlite();
    migrateDb(ds);
    DbAccountRepository accountRepository = new DbAccountRepository(ds);
    DbFolderRepository folderRepository = new DbFolderRepository(ds);
    accountRepository.createAccountIfNew(
        new Account(
            -1,
            "Checkpoint",
            "imap.example.test",
            993,
            "user",
            "secret",
            "smtp.example.test",
            587,
            "user",
            "secret"));
    Account account = accountRepository.getAccounts().getFirst();
    NamedFolder folder =
        folderRepository.createFolder(
            account, "Inbox", "INBOX", FolderSpecialUse.INBOX, 123456789L);

    NamedFolder updated = folderRepository.updateSyncCheckpoint(folder, 4242L, 1_700_000_000L);

    assertEquals(4242L, updated.highestModSeq());
    assertEquals(1_700_000_000L, updated.lastFullSyncEpochS());
    NamedFolder loaded = folderRepository.getFolder(folder.id());
    assertEquals(4242L, loaded.highestModSeq());
    assertEquals(1_700_000_000L, loaded.lastFullSyncEpochS());
  }

  @Test
  void storesLastViewedEpoch() throws Exception {
    DataSource ds = DbTestUtils.getTempSqlite();
    migrateDb(ds);
    DbAccountRepository accountRepository = new DbAccountRepository(ds);
    DbFolderRepository folderRepository = new DbFolderRepository(ds);
    accountRepository.createAccountIfNew(
        new Account(
            -1,
            "View Cursor",
            "imap.example.test",
            993,
            "user",
            "secret",
            "smtp.example.test",
            587,
            "user",
            "secret"));
    Account account = accountRepository.getAccounts().getFirst();
    NamedFolder folder =
        folderRepository.createFolder(
            account, "Inbox", "INBOX", FolderSpecialUse.INBOX, 123456789L);

    folderRepository.markFolderViewed(folder.id(), 1_700_000_100L);

    try (var con = ds.getConnection();
        var stmt = con.prepareStatement("SELECT last_viewed_epoch_s FROM folders WHERE id = ?")) {
      stmt.setInt(1, folder.id());
      try (var rs = stmt.executeQuery()) {
        assertTrue(rs.next());
        assertEquals(1_700_000_100L, rs.getLong(1));
      }
    }
  }

  @Test
  void deleteFolderClearsMappingAndDetachesMailboxActionReferences() throws Exception {
    DataSource ds = DbTestUtils.getTempSqlite();
    migrateDb(ds);
    DbAccountRepository accountRepository = new DbAccountRepository(ds);
    DbFolderRepository folderRepository = new DbFolderRepository(ds);
    DbAccountFolderMappingRepository mappingRepository = new DbAccountFolderMappingRepository(ds);
    accountRepository.createAccountIfNew(
        new Account(
            -1,
            "Delete Folder",
            "imap.example.test",
            993,
            "user",
            "secret",
            "smtp.example.test",
            587,
            "user",
            "secret"));
    Account account = accountRepository.getAccounts().getFirst();
    NamedFolder inbox =
        folderRepository.createFolder(
            account, "INBOX", "INBOX", FolderSpecialUse.INBOX, 1779700338L);
    mappingRepository.save(
        account.id(),
        FolderSpecialUse.INBOX,
        inbox.id(),
        inbox.remoteName(),
        FolderMappingStatus.USER_CONFIRMED);

    try (Connection con = ds.getConnection();
        PreparedStatement stmt =
            con.prepareStatement(
                """
                    INSERT INTO mailbox_action_queue (
                      account_id, action_type, source_folder_id, source_remote_name, status)
                    VALUES (?, 'MOVE', ?, 'INBOX', 'PENDING')
                    """)) {
      stmt.setInt(1, account.id());
      stmt.setInt(2, inbox.id());
      stmt.executeUpdate();
    }

    folderRepository.deleteFolder(inbox);

    assertTrue(folderRepository.getFolders(account.id()).isEmpty());
    try (Connection con = ds.getConnection();
        PreparedStatement mappingStmt =
            con.prepareStatement(
                "SELECT folder_id, status FROM account_folder_mappings WHERE account_id = ?")) {
      mappingStmt.setInt(1, account.id());
      try (var rs = mappingStmt.executeQuery()) {
        assertTrue(rs.next());
        assertNull(rs.getObject(1));
        assertEquals(FolderMappingStatus.MISSING.name(), rs.getString(2));
      }
    }
    try (Connection con = ds.getConnection();
        PreparedStatement actionStmt =
            con.prepareStatement(
                "SELECT source_folder_id, source_remote_name FROM mailbox_action_queue")) {
      try (var rs = actionStmt.executeQuery()) {
        assertTrue(rs.next());
        assertNull(rs.getObject(1));
        assertEquals("INBOX", rs.getString(2));
      }
    }
  }
}
