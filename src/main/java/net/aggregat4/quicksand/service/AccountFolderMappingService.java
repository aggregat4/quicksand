package net.aggregat4.quicksand.service;

import jakarta.mail.Folder;
import jakarta.mail.MessagingException;
import jakarta.mail.NoSuchProviderException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.aggregat4.quicksand.domain.Account;
import net.aggregat4.quicksand.domain.AccountFolderMapping;
import net.aggregat4.quicksand.domain.FolderMappingSetupRow;
import net.aggregat4.quicksand.domain.FolderMappingStatus;
import net.aggregat4.quicksand.domain.FolderSpecialUse;
import net.aggregat4.quicksand.domain.NamedFolder;
import net.aggregat4.quicksand.imap.FolderRemoteNameMatcher;
import net.aggregat4.quicksand.jobs.JakartaMailSessionProperties;
import net.aggregat4.quicksand.repository.AccountFolderMappingRepository;
import net.aggregat4.quicksand.repository.AccountRepository;
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
  private final AccountRepository accountRepository;

  public AccountFolderMappingService(
      AccountFolderMappingRepository mappingRepository,
      FolderRepository folderRepository,
      AccountRepository accountRepository) {
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
    Set<Integer> mappedFolderIds =
        mappingsByUse.values().stream()
            .map(AccountFolderMapping::folderId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    return REQUIRED_SPECIAL_USES.stream()
        .map(
            use -> {
              Integer currentFolderId =
                  Optional.ofNullable(mappingsByUse.get(use))
                      .map(AccountFolderMapping::folderId)
                      .orElse(null);
              Set<Integer> folderIdsMappedToOtherRoles =
                  mappedFolderIds.stream()
                      .filter(folderId -> !Objects.equals(folderId, currentFolderId))
                      .collect(Collectors.toUnmodifiableSet());
              return new FolderMappingSetupRow(
                  use, mappingsByUse.get(use), folders, folderIdsMappedToOtherRoles);
            })
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
    List<NamedFolder> folders = folderRepository.getFolders(accountId);
    for (AccountFolderMapping mapping : mappingRepository.findByAccountId(accountId)) {
      if (mapping.status() != FolderMappingStatus.AUTO_DETECTED || mapping.folderId() == null) {
        continue;
      }
      NamedFolder folder =
          folders.stream()
              .filter(candidate -> candidate.id() == mapping.folderId())
              .findFirst()
              .orElse(null);
      if (folder == null) {
        continue;
      }
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
    confirmAutoDetectedMappings(accountId);
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
    reconcileConfirmedMappings(accountId, folders);
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
        if (existing != null
            && existing.status() == FolderMappingStatus.AUTO_DETECTED
            && Objects.equals(existing.folderId(), folder.id())) {
          continue;
        }
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

  private void reconcileConfirmedMappings(int accountId, List<NamedFolder> folders) {
    Set<Integer> folderIds =
        folders.stream().map(NamedFolder::id).collect(Collectors.toUnmodifiableSet());
    for (AccountFolderMapping mapping : mappingRepository.findByAccountId(accountId)) {
      if (mapping.status() != FolderMappingStatus.USER_CONFIRMED) {
        continue;
      }
      Integer folderId = mapping.folderId();
      if (folderId != null && folderIds.contains(folderId)) {
        continue;
      }
      Optional<NamedFolder> matchedFolder =
          folders.stream()
              .filter(
                  folder ->
                      FolderRemoteNameMatcher.matchesStoredRemoteName(mapping.remoteName(), folder))
              .findFirst();
      if (matchedFolder.isPresent()) {
        NamedFolder folder = matchedFolder.get();
        mappingRepository.save(
            accountId,
            mapping.specialUse(),
            folder.id(),
            Optional.ofNullable(folder.remoteName()).orElse(folder.name()),
            FolderMappingStatus.USER_CONFIRMED);
        folderRepository.updateMappingStatus(folder, FolderMappingStatus.USER_CONFIRMED);
        continue;
      }
      mappingRepository.save(
          accountId, mapping.specialUse(), null, null, FolderMappingStatus.MISSING);
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
    Store store = createStore(account);
    store.connect(
        account.imapHost(), account.imapPort(), account.imapUsername(), account.imapPassword());
    return store;
  }

  private Store createStore(Account account) {
    try {
      return Session.getInstance(JakartaMailSessionProperties.imap(account), null).getStore("imap");
    } catch (NoSuchProviderException e) {
      throw new IllegalStateException("There is no imap provider, this should not happen.");
    }
  }
}
