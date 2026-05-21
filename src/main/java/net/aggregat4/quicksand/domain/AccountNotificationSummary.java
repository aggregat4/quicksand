package net.aggregat4.quicksand.domain;

import java.util.Map;

public record AccountNotificationSummary(
    int inboxNewSinceView, String inboxHref, Map<Integer, Integer> unreadByFolderId) {

  public AccountNotificationSummary {
    unreadByFolderId = Map.copyOf(unreadByFolderId);
  }

  public int unreadCount(int folderId) {
    return unreadByFolderId.getOrDefault(folderId, 0);
  }
}
