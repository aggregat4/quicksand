package net.aggregat4.quicksand.repository;

import net.aggregat4.quicksand.domain.Account;
import net.aggregat4.quicksand.domain.NamedFolder;

import java.util.List;

public interface FolderRepository {
    List<NamedFolder> getFolders(Account account);

    NamedFolder createFolder(Account account, String name);

    void deleteFolder(NamedFolder folder);
}
