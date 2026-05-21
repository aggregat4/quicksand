package net.aggregat4.quicksand.service;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.aggregat4.quicksand.domain.AccountNotificationSummary;
import net.aggregat4.quicksand.domain.FolderSpecialUse;
import net.aggregat4.quicksand.domain.NamedFolder;
import net.aggregat4.quicksand.repository.EmailRepository;
import net.aggregat4.quicksand.repository.FolderRepository;

public class NotificationService {

  private final FolderRepository folderRepository;
  private final EmailRepository emailRepository;
  private final Clock clock;

  public NotificationService(
      FolderRepository folderRepository, EmailRepository emailRepository, Clock clock) {
    this.folderRepository = folderRepository;
    this.emailRepository = emailRepository;
    this.clock = clock;
  }

  public AccountNotificationSummary getAccountSummary(int accountId) {
    List<NamedFolder> folders = folderRepository.getFolders(accountId);
    Map<Integer, Integer> unreadByFolderId = emailRepository.countUnreadByFolder(accountId);
    Map<Integer, Integer> unreadCounts = new HashMap<>();
    for (NamedFolder folder : folders) {
      int unread = unreadByFolderId.getOrDefault(folder.id(), 0);
      if (folder.specialUse() == FolderSpecialUse.SENT) {
        unread = 0;
      }
      unreadCounts.put(folder.id(), unread);
    }

    Optional<NamedFolder> inbox =
        folders.stream()
            .filter(folder -> folder.specialUse() == FolderSpecialUse.INBOX)
            .findFirst();
    int inboxNewSinceView =
        inbox.map(folder -> emailRepository.countNewSinceLastView(folder.id())).orElse(0);
    String inboxHref =
        inbox
            .map(folder -> "/accounts/%s/folders/%s".formatted(accountId, folder.id()))
            .orElse(null);

    return new AccountNotificationSummary(inboxNewSinceView, inboxHref, unreadCounts);
  }

  public void markFolderViewed(int folderId) {
    long viewedAtEpochS = emailRepository.maxReceivedEpochSeconds(folderId);
    if (viewedAtEpochS <= 0) {
      viewedAtEpochS = clock.instant().getEpochSecond();
    }
    folderRepository.markFolderViewed(folderId, viewedAtEpochS);
  }

  public boolean shouldShowInboxStrip(
      AccountNotificationSummary summary, Optional<Integer> currentFolderId, NamedFolder inbox) {
    if (summary.inboxNewSinceView() <= 0) {
      return false;
    }
    return currentFolderId.filter(folderId -> folderId == inbox.id()).isEmpty();
  }
}
