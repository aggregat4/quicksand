package net.aggregat4.quicksand.jobs;

import net.aggregat4.quicksand.domain.Account;
import net.aggregat4.quicksand.domain.NamedFolder;
import net.aggregat4.quicksand.repository.FolderRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class InMemoryFolderRepository implements FolderRepository {


    private Map<Account, List<NamedFolder>> foldersByAccount = new HashMap<>();

    @Override
    public List<NamedFolder> getFolders(Account account) {
        return foldersByAccount.computeIfAbsent(account, k -> new ArrayList<>());
    }

    @Override
    public NamedFolder createFolder(Account account, String name) {
        List<NamedFolder> folders = getFolders(account);
        NamedFolder folder = new NamedFolder(folders.size() + 1, name, -1);
        folders.add(folder);
        return folder;
    }

    @Override
    public void deleteFolder(NamedFolder folder) {
        for (List<NamedFolder> folders : foldersByAccount.values()) {
            folders.remove(folder);
        }
    }
}
