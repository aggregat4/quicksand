package net.aggregat4.quicksand.security;

import io.helidon.config.Config;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Reversible encryption for IMAP/SMTP secrets persisted in SQLite. */
public final class AccountCredentialCipher {

  public static final String ENV_VAR_NAME = "QUICKSAND_CREDENTIAL_KEY";
  public static final String SYSTEM_PROPERTY_NAME = "quicksand.credential.key";
  private static final String PREFIX = "qsenc1:";
  private static final int KEY_BYTES = 32;
  private static final int NONCE_BYTES = 12;
  private static final int GCM_TAG_BITS = 128;

  private final SecretKey secretKey;

  public AccountCredentialCipher(byte[] keyBytes) {
    if (keyBytes.length != KEY_BYTES) {
      throw new IllegalArgumentException(
          "Credential encryption key must be %d bytes, got %d"
              .formatted(KEY_BYTES, keyBytes.length));
    }
    this.secretKey = new SecretKeySpec(keyBytes.clone(), "AES");
  }

  public static AccountCredentialCipher load() {
    return load(Optional.empty());
  }

  public static AccountCredentialCipher load(Config config) {
    Optional<String> configKey = Optional.empty();
    if (config != null && config.get("credentials.encryption_key_base64").asString().isPresent()) {
      configKey = Optional.of(config.get("credentials.encryption_key_base64").asString().get());
    }
    return load(configKey);
  }

  public static AccountCredentialCipher load(Optional<String> configKeyBase64) {
    String keyBase64 =
        firstNonBlank(
                Optional.ofNullable(System.getenv(ENV_VAR_NAME)),
                Optional.ofNullable(System.getProperty(SYSTEM_PROPERTY_NAME)),
                configKeyBase64)
            .orElseThrow(AccountCredentialCipher::missingKeyError);
    return fromBase64Key(keyBase64);
  }

  public static AccountCredentialCipher fromBase64Key(String keyBase64) {
    byte[] keyBytes;
    try {
      keyBytes = Base64.getDecoder().decode(keyBase64.trim());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Credential encryption key is not valid base64", e);
    }
    return new AccountCredentialCipher(keyBytes);
  }

  public String encrypt(String plaintext) {
    if (plaintext == null || plaintext.isEmpty()) {
      return plaintext;
    }
    try {
      byte[] nonce = new byte[NONCE_BYTES];
      new SecureRandom().nextBytes(nonce);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, nonce));
      byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
      byte[] blob = new byte[nonce.length + ciphertext.length];
      System.arraycopy(nonce, 0, blob, 0, nonce.length);
      System.arraycopy(ciphertext, 0, blob, nonce.length, ciphertext.length);
      return PREFIX + Base64.getEncoder().encodeToString(blob);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Failed to encrypt account credential", e);
    }
  }

  public String decrypt(String stored) {
    if (stored == null || stored.isEmpty()) {
      return stored;
    }
    if (!stored.startsWith(PREFIX)) {
      return stored;
    }
    try {
      byte[] blob = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
      if (blob.length <= NONCE_BYTES) {
        throw new IllegalArgumentException("Encrypted credential blob is too short");
      }
      byte[] nonce = new byte[NONCE_BYTES];
      byte[] ciphertext = new byte[blob.length - NONCE_BYTES];
      System.arraycopy(blob, 0, nonce, 0, NONCE_BYTES);
      System.arraycopy(blob, NONCE_BYTES, ciphertext, 0, ciphertext.length);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, nonce));
      byte[] plaintext = cipher.doFinal(ciphertext);
      return new String(plaintext, StandardCharsets.UTF_8);
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      throw new IllegalStateException("Failed to decrypt account credential", e);
    }
  }

  public boolean isEncrypted(String stored) {
    return stored != null && stored.startsWith(PREFIX);
  }

  public boolean needsEncryption(String stored) {
    return stored != null && !stored.isEmpty() && !isEncrypted(stored);
  }

  private static Optional<String> firstNonBlank(
      Optional<String> first, Optional<String> second, Optional<String> third) {
    for (Optional<String> value : List.of(first, second, third)) {
      if (value.isPresent() && !value.get().isBlank()) {
        return value;
      }
    }
    return Optional.empty();
  }

  private static IllegalStateException missingKeyError() {
    return new IllegalStateException(
        """
            Missing credential encryption key. Set one of:
              - environment variable %s (base64-encoded 32-byte key)
              - JVM property -D%s=...
              - credentials.encryption_key_base64 in application config

            Generate a key: openssl rand -base64 32
            See docs/account-credentials.md."""
            .formatted(ENV_VAR_NAME, SYSTEM_PROPERTY_NAME));
  }
}
