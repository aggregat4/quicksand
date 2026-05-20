package net.aggregat4.quicksand.service;

import jakarta.mail.Folder;
import jakarta.mail.MessagingException;
import jakarta.mail.NoSuchProviderException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.aggregat4.quicksand.domain.Account;
import net.aggregat4.quicksand.domain.AccountFolderMapping;
import net.aggregat4.quicksand.domain.FolderMappingSetupRow;
import net.aggregat4.quicksand.domain.FolderMappingStatus;
import net.aggregat4.quicksand.domain.FolderSpecialUse;
import net.aggregat4.quicksand.domain.NamedFolder;
import net.aggregat4.quicksand.repository.AccountFolderMappingRepository;
import net.aggregat4.quicksand.repository.DbAccountRepository;
import net.aggregat4.quicksand.repository.FolderRepository;

public class AccountFolderMappingService {
  private static final List<FolderSpecialUse> REQUIRED_SPECIAL_USES =
      List.copyOf(
          EnumSet.of(
              FolderSpecialUse.ARCHIVE,
              FolderSpecialUse.TRASH,
              FolderSpecialUse.JUNK,
              FolderSpecialUse.SENT,
              FolderSpecialUse.DRAFTS));

  private final AccountFolderMappingRepository mappingRepository;
  private final FolderRepository folderRepository;
  private final DbAccountRepository accountRepository;

  public AccountFolderMappingService(
      AccountFolderMappingRepository mappingRepository,
      FolderRepository folderRepository,
      DbAccountRepository accountRepository) {
    this.mappingRepository = mappingRepository;
    this.folderRepository = folderRepository;
    this.accountRepository = accountRepository;
  }

  public List<FolderMappingSetupRow> getSetupRows(int accountId) {
    autoDetectMappings(accountId);
    List<NamedFolder> folders = folderRepository.getFolders(accountId);
    Map<FolderSpecialUse, AccountFolderMapping> mappingsByUse =
        mappingRepository.findByAccountId(accountId).stream()
            .collect(Collectors.toMap(AccountFolderMapping::specialUse, Function.identity()));
    return REQUIRED_SPECIAL_USES.stream()
        .map(use -> new FolderMappingSetupRow(use, mappingsByUse.get(use), folders))
        .toList();
  }

  public boolean hasRequiredMappings(int accountId) {
    return getSetupRows(accountId).stream().allMatch(FolderMappingSetupRow::configured);
  }

  public boolean hasConfiguredMapping(int accountId, FolderSpecialUse specialUse) {
    autoDetectMappings(accountId);
    return mappingRepository.findByAccountId(accountId).stream()
        .filter(mapping -> mapping.specialUse() == specialUse)
        .findFirst()
        .map(AccountFolderMapping::configured)
        .orElse(false);
  }

  public List<FolderSpecialUse> requiredSpecialUses() {
    return REQUIRED_SPECIAL_USES;
  }

  public void syncMappingsAfterFolderDiscovery(int accountId) {
    autoDetectMappings(accountId);
  }

  public boolean hasAutoDetectedMappings(int accountId) {
    autoDetectMappings(accountId);
    return mappingRepository.findByAccountId(accountId).stream()
        .anyMatch(mapping -> mapping.status() == FolderMappingStatus.AUTO_DETECTED);
  }

  public void confirmAutoDetectedMappings(int accountId) {
    for (AccountFolderMapping mapping : mappingRepository.findByAccountId(accountId)) {
      if (mapping.status() != FolderMappingStatus.AUTO_DETECTED || mapping.folderId() == null) {
        continue;
      }
      NamedFolder folder = folderRepository.getFolder(mapping.folderId());
      mappingRepository.save(
          accountId,
          mapping.specialUse(),
          folder.id(),
          Optional.ofNullable(mapping.remoteName())
              .orElse(Optional.ofNullable(folder.remoteName()).orElse(folder.name())),
          FolderMappingStatus.USER_CONFIRMED);
      folderRepository.updateMappingStatus(folder, FolderMappingStatus.USER_CONFIRMED);
    }
  }

  public void saveExistingFolderMapping(int accountId, FolderSpecialUse specialUse, int folderId) {
    saveExistingFolderMappings(accountId, Map.of(specialUse, folderId));
  }

  public void saveExistingFolderMappings(
      int accountId, Map<FolderSpecialUse, Integer> folderIdsBySpecialUse) {
    rejectDuplicateFolderSelections(folderIdsBySpecialUse);
    for (Map.Entry<FolderSpecialUse, Integer> entry : folderIdsBySpecialUse.entrySet()) {
      saveValidatedExistingFolderMapping(accountId, entry.getKey(), entry.getValue());
    }
  }

  private void saveValidatedExistingFolderMapping(
      int accountId, FolderSpecialUse specialUse, int folderId) {
    NamedFolder folder =
        folderRepository.getFolders(accountId).stream()
            .filter(candidate -> candidate.id() == folderId)
            .findFirst()
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Folder %s does not belong to account %s".formatted(folderId, accountId)));
    if (folder.specialUse() == FolderSpecialUse.INBOX) {
      throw new IllegalArgumentException("INBOX cannot be used as a special action folder.");
    }
    mappingRepository.save(
        accountId,
        specialUse,
        folder.id(),
        Optional.ofNullable(folder.remoteName()).orElse(folder.name()),
        FolderMappingStatus.USER_CONFIRMED);
    folderRepository.updateMappingStatus(folder, FolderMappingStatus.USER_CONFIRMED);
  }

  private void rejectDuplicateFolderSelections(
      Map<FolderSpecialUse, Integer> folderIdsBySpecialUse) {
    Set<Integer> uniqueFolderIds = new java.util.HashSet<>();
    for (int folderId : folderIdsBySpecialUse.values()) {
      if (!uniqueFolderIds.add(folderId)) {
        throw new IllegalArgumentException(
            "The same server folder cannot be used for multiple required mappings.");
      }
    }
  }

  public void createRemoteFolderAndMap(
      int accountId, FolderSpecialUse specialUse, String remoteName) throws MessagingException {
    if (remoteName == null || remoteName.isBlank()) {
      throw new IllegalArgumentException("Remote folder name must not be blank.");
    }
    Account account = accountRepository.getAccount(accountId);
    try (Store store = createConnectedStore(account)) {
      Folder remoteFolder = store.getFolder(remoteName.trim());
      if (!remoteFolder.exists() && !remoteFolder.create(Folder.HOLDS_MESSAGES)) {
        throw new MessagingException("IMAP server did not create folder " + remoteName);
      }
    }
    NamedFolder localFolder =
        findByRemoteName(accountId, remoteName.trim())
            .orElseGet(
                () ->
                    folderRepository.createFolder(
                        account, remoteName.trim(), remoteName.trim(), specialUse, null));
    localFolder =
        folderRepository.updateRemoteMetadata(
            localFolder, remoteName.trim(), specialUse, localFolder.uidValidity());
    mappingRepository.save(
        accountId,
        specialUse,
        localFolder.id(),
        remoteName.trim(),
        FolderMappingStatus.USER_CONFIRMED);
    folderRepository.updateMappingStatus(localFolder, FolderMappingStatus.USER_CONFIRMED);
  }

  private void autoDetectMappings(int accountId) {
    List<NamedFolder> folders = folderRepository.getFolders(accountId);
    Map<FolderSpecialUse, AccountFolderMapping> existingMappings =
        mappingRepository.findByAccountId(accountId).stream()
            .collect(Collectors.toMap(AccountFolderMapping::specialUse, Function.identity()));
    for (FolderSpecialUse specialUse : REQUIRED_SPECIAL_USES) {
      AccountFolderMapping existing = existingMappings.get(specialUse);
      if (existing != null && existing.status() == FolderMappingStatus.USER_CONFIRMED) {
        continue;
      }
      List<NamedFolder> candidates =
          folders.stream().filter(folder -> folder.specialUse() == specialUse).toList();
      if (candidates.size() == 1) {
        NamedFolder folder = candidates.getFirst();
        mappingRepository.save(
            accountId,
            specialUse,
            folder.id(),
            Optional.ofNullable(folder.remoteName()).orElse(folder.name()),
            FolderMappingStatus.AUTO_DETECTED);
        folderRepository.updateMappingStatus(folder, FolderMappingStatus.AUTO_DETECTED);
      } else if (candidates.size() > 1) {
        mappingRepository.save(accountId, specialUse, null, null, FolderMappingStatus.CONFLICT);
      } else if (existing == null) {
        mappingRepository.save(accountId, specialUse, null, null, FolderMappingStatus.MISSING);
      }
    }
  }

  private Optional<NamedFolder> findByRemoteName(int accountId, String remoteName) {
    return folderRepository.getFolders(accountId).stream()
        .filter(
            folder ->
                remoteName.equals(folder.remoteName())
                    || remoteName.equalsIgnoreCase(folder.name()))
        .findFirst();
  }

  private Store createConnectedStore(Account account) throws MessagingException {
    Store store = createStore();
    store.connect(
        account.imapHost(), account.imapPort(), account.imapUsername(), account.imapPassword());
    return store;
  }

  private Store createStore() {
    try {
      return Session.getInstance(new Properties(), null).getStore("imap");
    } catch (NoSuchProviderException e) {
      throw new IllegalStateException("There is no imap provider, this should not happen.");
    }
  }
}
