package net.aggregat4.quicksand.repository;

import java.util.List;
import net.aggregat4.quicksand.domain.Account;
import net.aggregat4.quicksand.domain.FolderMappingStatus;
import net.aggregat4.quicksand.domain.FolderSpecialUse;
import net.aggregat4.quicksand.domain.NamedFolder;

public interface FolderRepository {
  List<NamedFolder> getFolders(int accountId);

  default NamedFolder createFolder(Account account, String name) {
    return createFolder(account, name, null, null, null);
  }

  NamedFolder createFolder(
      Account account,
      String name,
      String remoteName,
      FolderSpecialUse specialUse,
      Long uidValidity);

  NamedFolder updateRemoteMetadata(
      NamedFolder folder, String remoteName, FolderSpecialUse specialUse, Long uidValidity);

  NamedFolder updateSyncCheckpoint(NamedFolder folder, Long highestModSeq, Long lastFullSyncEpochS);

  void updateMappingStatus(NamedFolder folder, FolderMappingStatus mappingStatus);

  void deleteFolder(NamedFolder folder);

  NamedFolder getFolder(int folderId);
}
