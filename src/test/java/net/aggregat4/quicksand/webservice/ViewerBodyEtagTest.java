package net.aggregat4.quicksand.webservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class ViewerBodyEtagTest {

  @Test
  void etagChangesWhenShowImagesOrQueryChanges() {
    String hash = "abc123def4567890";
    String base = ViewerBodyEtag.fromPersistedHash(hash, false, "");
    String showImages = ViewerBodyEtag.fromPersistedHash(hash, true, "");
    String withQuery = ViewerBodyEtag.fromPersistedHash(hash, false, "invoice");

    assertEquals(base, ViewerBodyEtag.fromPersistedHash(hash, false, ""));
    assertNotEquals(base, showImages);
    assertNotEquals(base, withQuery);
  }
}
