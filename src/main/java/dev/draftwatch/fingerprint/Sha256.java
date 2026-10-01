package dev.draftwatch.fingerprint;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** SHA-256 helpers. Every digest in draftwatch is SHA-256, written as lowercase hex. */
public final class Sha256 {
  private static final char[] HEX = "0123456789abcdef".toCharArray();

  private Sha256() {}

  /** A fresh digest; SHA-256 is required of every Java platform by {@link MessageDigest}. */
  public static MessageDigest newDigest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available on this JVM", e);
    }
  }

  /** Lowercase hex SHA-256 of {@code data}. */
  public static String hex(byte[] data) {
    return toHex(newDigest().digest(data));
  }

  /** Lowercase hex encoding of {@code bytes}. */
  public static String toHex(byte[] bytes) {
    char[] out = new char[bytes.length * 2];
    for (int i = 0; i < bytes.length; i++) {
      out[2 * i] = HEX[(bytes[i] >> 4) & 0xf];
      out[2 * i + 1] = HEX[bytes[i] & 0xf];
    }
    return new String(out);
  }
}
