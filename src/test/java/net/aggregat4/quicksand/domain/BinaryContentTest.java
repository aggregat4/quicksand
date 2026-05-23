package net.aggregat4.quicksand.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class BinaryContentTest {

  @Test
  void equalsUsesByteContentsNotReferenceIdentity() {
    BinaryContent left = BinaryContent.of(new byte[] {1, 2, 3});
    BinaryContent right = BinaryContent.of(new byte[] {1, 2, 3});
    BinaryContent different = BinaryContent.of(new byte[] {9});

    assertEquals(left, right);
    assertNotEquals(left, different);
    assertEquals(left.hashCode(), right.hashCode());
  }
}
