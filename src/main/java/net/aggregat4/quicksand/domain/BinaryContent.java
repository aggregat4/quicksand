package net.aggregat4.quicksand.domain;

import java.util.Arrays;
import java.util.Objects;

public final class BinaryContent {
  private final byte[] bytes;

  private BinaryContent(byte[] bytes) {
    this.bytes = Objects.requireNonNull(bytes, "bytes").clone();
  }

  public static BinaryContent of(byte[] bytes) {
    return new BinaryContent(bytes);
  }

  public byte[] bytes() {
    return bytes.clone();
  }

  public int size() {
    return bytes.length;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof BinaryContent that)) {
      return false;
    }
    return Arrays.equals(bytes, that.bytes);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(bytes);
  }

  @Override
  public String toString() {
    return "BinaryContent[size=" + bytes.length + "]";
  }
}
