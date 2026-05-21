package net.aggregat4.quicksand.jobs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.aggregat4.quicksand.domain.Account;
import net.aggregat4.quicksand.domain.FolderMappingStatus;
import net.aggregat4.quicksand.domain.FolderSpecialUse;
import net.aggregat4.quicksand.domain.NamedFolder;
import net.aggregat4.quicksand.repository.FolderRepository;

class InMemoryFolderRepository implements FolderRepository {

  private final Map<Integer, List<NamedFolder>> foldersByAccount = new HashMap<>();

  @Override
  public List<NamedFolder> getFolders(int accountId) {
    return foldersByAccount.computeIfAbsent(accountId, k -> new ArrayList<>());
  }

  @Override
  public NamedFolder createFolder(
      Account account,
      String name,
      String remoteName,
      FolderSpecialUse specialUse,
      Long uidValidity) {
    List<NamedFolder> folders = getFolders(account.id());
    NamedFolder folder =
        new NamedFolder(
            folders.size() + 1,
            name,
            -1,
            remoteName,
            specialUse,
            uidValidity,
            true,
            FolderMappingStatus.MISSING,
            null,
            null);
    folders.add(folder);
    return folder;
  }

  @Override
  public NamedFolder updateRemoteMetadata(
      NamedFolder folder, String remoteName, FolderSpecialUse specialUse, Long uidValidity) {
    return replaceFolder(
        folder,
        new NamedFolder(
            folder.id(),
            folder.name(),
            folder.lastSeenUid(),
            remoteName,
            specialUse,
            uidValidity,
            folder.syncEnabled(),
            folder.mappingStatus(),
            folder.highestModSeq(),
            folder.lastFullSyncEpochS()));
  }

  @Override
  public NamedFolder updateSyncCheckpoint(
      NamedFolder folder, Long highestModSeq, Long lastFullSyncEpochS) {
    return replaceFolder(folder, folder.withSyncCheckpoint(highestModSeq, lastFullSyncEpochS));
  }

  @Override
  public void updateMappingStatus(NamedFolder folder, FolderMappingStatus mappingStatus) {
    replaceFolder(
        folder,
        new NamedFolder(
            folder.id(),
            folder.name(),
            folder.lastSeenUid(),
            folder.remoteName(),
            folder.specialUse(),
            folder.uidValidity(),
            folder.syncEnabled(),
            mappingStatus,
            folder.highestModSeq(),
            folder.lastFullSyncEpochS()));
  }

  @Override
  public void deleteFolder(NamedFolder folder) {
    for (List<NamedFolder> folders : foldersByAccount.values()) {
      folders.remove(folder);
    }
  }

  @Override
  public NamedFolder getFolder(int folderId) {
    for (List<NamedFolder> folders : foldersByAccount.values()) {
      for (NamedFolder folder : folders) {
        if (folder.id() == folderId) {
          return folder;
        }
      }
    }
    throw new IllegalStateException("Folder not found: " + folderId);
  }

  private NamedFolder replaceFolder(NamedFolder folder, NamedFolder updated) {
    for (List<NamedFolder> folders : foldersByAccount.values()) {
      int index = folders.indexOf(folder);
      if (index >= 0) {
        folders.set(index, updated);
        return updated;
      }
    }
    throw new IllegalStateException("Folder not found: " + folder.id());
  }
}
