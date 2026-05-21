package net.aggregat4.quicksand.domain;

public record SidebarFolderLink(
    String name, String href, boolean selected, int unreadCount, int folderId) {

  public SidebarFolderLink(String name, String href, boolean selected) {
    this(name, href, selected, 0, -1);
  }
}
