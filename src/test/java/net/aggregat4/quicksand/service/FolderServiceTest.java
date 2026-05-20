package net.aggregat4.quicksand.service;

import static net.aggregat4.quicksand.repository.DatabaseMaintenance.migrateDb;
import static org.junit.jupiter.api.Assertions.assertEquals;

import javax.sql.DataSource;
import net.aggregat4.quicksand.DbTestUtils;
import net.aggregat4.quicksand.domain.Account;
import net.aggregat4.quicksand.domain.FolderSpecialUse;
import net.aggregat4.quicksand.repository.DbAccountRepository;
import net.aggregat4.quicksand.repository.DbFolderRepository;
import org.junit.jupiter.api.Test;

class FolderServiceTest {

  @Test
  void getFoldersListsInboxFirstThenSortsRemainingByName() throws Exception {
    DataSource ds = DbTestUtils.getTempSqlite();
    migrateDb(ds);
    DbAccountRepository accountRepository = new DbAccountRepository(ds);
    accountRepository.createAccountIfNew(
        new Account(-1, "Test", "imap", 143, "u", "p", "smtp", 587, "u", "p"));
    Account account = accountRepository.getAccounts().getFirst();
    DbFolderRepository folderRepository = new DbFolderRepository(ds);
    folderRepository.createFolder(account, "Sent", "Sent", FolderSpecialUse.SENT, 1L);
    folderRepository.createFolder(account, "INBOX", "INBOX", FolderSpecialUse.INBOX, 2L);
    folderRepository.createFolder(account, "Archive", "Archive", FolderSpecialUse.ARCHIVE, 3L);

    FolderService service = new FolderService(folderRepository);

    assertEquals(
        java.util.List.of("INBOX", "Archive", "Sent"),
        service.getFolders(account.id()).stream().map(folder -> folder.name()).toList());
  }
}
