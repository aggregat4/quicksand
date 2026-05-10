package net.aggregat4.quicksand.service;

import static net.aggregat4.quicksand.repository.DatabaseMaintenance.migrateDb;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import javax.sql.DataSource;
import net.aggregat4.quicksand.DbTestUtils;
import net.aggregat4.quicksand.domain.Account;
import net.aggregat4.quicksand.domain.FolderMappingStatus;
import net.aggregat4.quicksand.domain.FolderSpecialUse;
import net.aggregat4.quicksand.domain.NamedFolder;
import net.aggregat4.quicksand.repository.DbAccountFolderMappingRepository;
import net.aggregat4.quicksand.repository.DbAccountRepository;
import net.aggregat4.quicksand.repository.DbFolderRepository;
import org.junit.jupiter.api.Test;

class AccountFolderMappingServiceTest {

  @Test
  void autoDetectsUnambiguousSpecialUseFoldersAndSavesUserMappings() throws Exception {
    DataSource ds = DbTestUtils.getTempSqlite();
    migrateDb(ds);
    DbAccountRepository accountRepository = new DbAccountRepository(ds);
    accountRepository.createAccountIfNew(
        new Account(-1, "Mappings", "imap", 143, "u", "p", "smtp", 587, "u", "p"));
    Account account = accountRepository.getAccounts().getFirst();
    DbFolderRepository folderRepository = new DbFolderRepository(ds);
    DbAccountFolderMappingRepository mappingRepository = new DbAccountFolderMappingRepository(ds);
    NamedFolder archive =
        folderRepository.createFolder(
            account, "Archive", "Archive", FolderSpecialUse.ARCHIVE, 123L);
    NamedFolder trash =
        folderRepository.createFolder(account, "Trash", "Trash", FolderSpecialUse.TRASH, 456L);
    AccountFolderMappingService service =
        new AccountFolderMappingService(mappingRepository, folderRepository, accountRepository);

    var rows = service.getSetupRows(account.id());

    assertFalse(service.hasRequiredMappings(account.id()));
    var archiveMapping =
        mappingRepository.findByAccountId(account.id()).stream()
            .filter(mapping -> mapping.specialUse() == FolderSpecialUse.ARCHIVE)
            .findFirst()
            .orElseThrow();
    assertEquals(archive.id(), archiveMapping.folderId());
    assertEquals(FolderMappingStatus.AUTO_DETECTED, archiveMapping.status());

    service.saveExistingFolderMapping(account.id(), FolderSpecialUse.JUNK, trash.id());

    var junkMapping =
        mappingRepository.findByAccountId(account.id()).stream()
            .filter(mapping -> mapping.specialUse() == FolderSpecialUse.JUNK)
            .findFirst()
            .orElseThrow();
    assertEquals(trash.id(), junkMapping.folderId());
    assertEquals(FolderMappingStatus.USER_CONFIRMED, junkMapping.status());
    assertTrue(rows.stream().anyMatch(row -> row.specialUse() == FolderSpecialUse.DRAFTS));
  }

  @Test
  void rejectsMappingsToAnotherAccountFolder() throws Exception {
    DataSource ds = DbTestUtils.getTempSqlite();
    migrateDb(ds);
    DbAccountRepository accountRepository = new DbAccountRepository(ds);
    accountRepository.createAccountIfNew(
        new Account(-1, "First", "imap", 143, "u", "p", "smtp", 587, "u", "p"));
    accountRepository.createAccountIfNew(
        new Account(-1, "Second", "imap", 143, "u", "p", "smtp", 587, "u", "p"));
    Account first = accountRepository.getAccounts().get(0);
    Account second = accountRepository.getAccounts().get(1);
    DbFolderRepository folderRepository = new DbFolderRepository(ds);
    int secondAccountFolderId =
        folderRepository
            .createFolder(second, "Second Trash", "Trash", FolderSpecialUse.TRASH, 1L)
            .id();
    AccountFolderMappingService service =
        new AccountFolderMappingService(
            new DbAccountFolderMappingRepository(ds), folderRepository, accountRepository);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.saveExistingFolderMapping(
                first.id(), FolderSpecialUse.TRASH, secondAccountFolderId));
  }

  @Test
  void rejectsDuplicateFolderMappings() throws Exception {
    DataSource ds = DbTestUtils.getTempSqlite();
    migrateDb(ds);
    DbAccountRepository accountRepository = new DbAccountRepository(ds);
    accountRepository.createAccountIfNew(
        new Account(-1, "Mappings", "imap", 143, "u", "p", "smtp", 587, "u", "p"));
    Account account = accountRepository.getAccounts().getFirst();
    DbFolderRepository folderRepository = new DbFolderRepository(ds);
    int folderId =
        folderRepository
            .createFolder(account, "Archive", "Archive", FolderSpecialUse.ARCHIVE, 1L)
            .id();
    AccountFolderMappingService service =
        new AccountFolderMappingService(
            new DbAccountFolderMappingRepository(ds), folderRepository, accountRepository);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.saveExistingFolderMappings(
                account.id(),
                Map.of(FolderSpecialUse.ARCHIVE, folderId, FolderSpecialUse.TRASH, folderId)));
  }

  @Test
  void rejectsInboxAsRequiredMappingTarget() throws Exception {
    DataSource ds = DbTestUtils.getTempSqlite();
    migrateDb(ds);
    DbAccountRepository accountRepository = new DbAccountRepository(ds);
    accountRepository.createAccountIfNew(
        new Account(-1, "Mappings", "imap", 143, "u", "p", "smtp", 587, "u", "p"));
    Account account = accountRepository.getAccounts().getFirst();
    DbFolderRepository folderRepository = new DbFolderRepository(ds);
    int inboxId =
        folderRepository.createFolder(account, "Inbox", "INBOX", FolderSpecialUse.INBOX, 1L).id();
    AccountFolderMappingService service =
        new AccountFolderMappingService(
            new DbAccountFolderMappingRepository(ds), folderRepository, accountRepository);

    assertThrows(
        IllegalArgumentException.class,
        () -> service.saveExistingFolderMapping(account.id(), FolderSpecialUse.ARCHIVE, inboxId));
  }
}
