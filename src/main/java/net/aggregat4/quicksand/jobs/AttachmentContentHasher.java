package net.aggregat4.quicksand.jobs;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class AttachmentContentHasher {
  private AttachmentContentHasher() {}

  static String sha256Hex(byte[] content) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(content);
      StringBuilder sb = new StringBuilder(hash.length * 2);
      for (byte b : hash) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 digest is unavailable", e);
    }
  }
}
