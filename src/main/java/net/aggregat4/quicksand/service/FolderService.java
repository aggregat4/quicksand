package net.aggregat4.quicksand.service;

import net.aggregat4.quicksand.repository.FolderRepository;

public class FolderService {

    private final FolderRepository folderRepository;

    public FolderService(FolderRepository folderRepository) {
        this.folderRepository = folderRepository;
    }
}
