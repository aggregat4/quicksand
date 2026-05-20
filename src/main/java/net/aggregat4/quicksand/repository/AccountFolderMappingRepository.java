package net.aggregat4.quicksand.repository;

import java.util.List;
import net.aggregat4.quicksand.domain.AccountFolderMapping;
import net.aggregat4.quicksand.domain.FolderMappingStatus;
import net.aggregat4.quicksand.domain.FolderSpecialUse;

public interface AccountFolderMappingRepository {

  List<AccountFolderMapping> findByAccountId(int accountId);

  void save(
      int accountId,
      FolderSpecialUse specialUse,
      Integer folderId,
      String remoteName,
      FolderMappingStatus status);

  void markMappedFoldersMissing(int accountId);
}
