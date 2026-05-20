package net.aggregat4.quicksand.service;

import java.util.Comparator;
import java.util.List;
import net.aggregat4.quicksand.domain.FolderSpecialUse;
import net.aggregat4.quicksand.domain.NamedFolder;
import net.aggregat4.quicksand.repository.FolderRepository;

public class FolderService {

  private final FolderRepository folderRepository;

  public FolderService(FolderRepository folderRepository) {
    this.folderRepository = folderRepository;
  }

  public NamedFolder getFolder(int folderId) {
    return folderRepository.getFolder(folderId);
  }

  public List<NamedFolder> getFolders(int accountId) {
    return folderRepository.getFolders(accountId).stream().sorted(mailboxFolderOrder()).toList();
  }

  private static Comparator<NamedFolder> mailboxFolderOrder() {
    return Comparator.comparing(
            (NamedFolder folder) -> folder.specialUse() == FolderSpecialUse.INBOX ? 0 : 1)
        .thenComparing(NamedFolder::name, String.CASE_INSENSITIVE_ORDER);
  }
}
