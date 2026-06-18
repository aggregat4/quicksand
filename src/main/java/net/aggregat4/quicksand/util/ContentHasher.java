package net.aggregat4.quicksand.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class ContentHasher {
  private ContentHasher() {}

  public static String sha256Hex(byte[] content) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(content);
      StringBuilder builder = new StringBuilder(hash.length * 2);
      for (byte value : hash) {
        builder.append(String.format("%02x", value));
      }
      return builder.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }

  public static String sha256Hex(String content) {
    return sha256Hex(content.getBytes(StandardCharsets.UTF_8));
  }

  /** Short stable fingerprint used in ETags and static asset query params. */
  public static String shortHash(String content) {
    return sha256Hex(content).substring(0, 16);
  }

  public static String shortHash(byte[] content) {
    return sha256Hex(content).substring(0, 16);
  }

  public static String messageBodyContentHash(String body) {
    if (body == null || body.isBlank()) {
      return "";
    }
    return shortHash(body);
  }
}
