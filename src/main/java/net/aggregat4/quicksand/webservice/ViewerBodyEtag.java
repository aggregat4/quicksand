package net.aggregat4.quicksand.webservice;

import net.aggregat4.quicksand.util.ContentHasher;

final class ViewerBodyEtag {
  private ViewerBodyEtag() {}

  static String fromPersistedHash(String bodyContentHash, boolean showImages, String query) {
    String composite =
        bodyContentHash + "|" + showImages + "|" + (query == null ? "" : query.trim());
    return ResponseUtils.strongEtag(ContentHasher.shortHash(composite));
  }
}
