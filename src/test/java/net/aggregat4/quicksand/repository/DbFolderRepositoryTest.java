package net.aggregat4.quicksand.repository;

import static net.aggregat4.quicksand.repository.DatabaseMaintenance.migrateDb;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
