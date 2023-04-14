package net.aggregat4.quicksand.repository;

import net.aggregat4.quicksand.domain.Account;
import net.aggregat4.quicksand.domain.NamedFolder;

import java.util.List;

public interface FolderRepository {
    List<NamedFolder> getFolders(int accountId);

    NamedFolder createFolder(Account account, String name);

    void deleteFolder(NamedFolder folder);

    NamedFolder getFolder(int folderId);
}
