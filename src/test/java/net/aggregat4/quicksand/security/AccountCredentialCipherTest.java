package net.aggregat4.quicksand.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class AccountCredentialCipherTest {

  private static final byte[] TEST_KEY =
      "01234567890123456789012345678901".getBytes(java.nio.charset.StandardCharsets.UTF_8);

  @Test
  void roundTripEncryptsAndDecrypts() {
    AccountCredentialCipher cipher = new AccountCredentialCipher(TEST_KEY);
    String encrypted = cipher.encrypt("app-password-xy");
    assertTrue(encrypted.startsWith("qsenc1:"));
    assertEquals("app-password-xy", cipher.decrypt(encrypted));
  }

  @Test
  void decryptReturnsLegacyPlaintext() {
    AccountCredentialCipher cipher = new AccountCredentialCipher(TEST_KEY);
    assertEquals("legacy-plain", cipher.decrypt("legacy-plain"));
  }

  @Test
  void rejectsWrongKeyLength() {
    assertThrows(
        IllegalArgumentException.class, () -> new AccountCredentialCipher(new byte[] {1, 2, 3}));
  }

  @Test
  void loadFromBase64KeyMatchesTestFixture() {
    String base64 = Base64.getEncoder().encodeToString(TEST_KEY);
    AccountCredentialCipher cipher = AccountCredentialCipher.fromBase64Key(base64);
    assertFalse(cipher.needsEncryption(cipher.encrypt("secret")));
    assertTrue(cipher.needsEncryption("still-plain"));
  }
}
