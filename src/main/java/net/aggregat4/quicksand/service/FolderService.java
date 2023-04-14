package net.aggregat4.quicksand.service;

import net.aggregat4.quicksand.domain.NamedFolder;
import net.aggregat4.quicksand.repository.FolderRepository;

import java.util.List;

public class FolderService {

    private final FolderRepository folderRepository;

    public FolderService(FolderRepository folderRepository) {
        this.folderRepository = folderRepository;
    }

    public NamedFolder getFolder(int folderId) {
        return folderRepository.getFolder(folderId);
    }

    public List<NamedFolder> getFolders(int accountId) {
        return folderRepository.getFolders(accountId);
    }
}
