package dev.draftwatch.fingerprint;

import java.nio.charset.StandardCharsets;

/**
 * The identity of an adapter checkpoint: its adapter weights together with the base model they
 * are merged into. The same adapter on a different base model is a different
 * model, so it must get a different fingerprint.
 */
public final class AdapterFingerprint {
  private AdapterFingerprint() {}

  /**
   * {@code <method>-<SHA-256 of "adapter" NUL <adapter fingerprint> NUL "base" NUL <base
   * fingerprint> LF>}, in the method of both inputs.
   *
   * @throws IllegalArgumentException if the two fingerprints were made by different methods
   */
  public static String combine(String adapterFingerprint, String baseFingerprint) {
    String method = method(adapterFingerprint);
    if (!method.equals(method(baseFingerprint))) {
      throw new IllegalArgumentException(
          "adapter and base fingerprints use different methods: " + adapterFingerprint + ", "
              + baseFingerprint);
    }
    String input = "adapter\0" + adapterFingerprint + "\0base\0" + baseFingerprint + "\n";
    return method + "-" + Sha256.hex(input.getBytes(StandardCharsets.UTF_8));
  }

  private static String method(String fingerprint) {
    int dash = fingerprint.indexOf('-');
    if (dash <= 0) {
      throw new IllegalArgumentException("not a <method>-<hex> fingerprint: " + fingerprint);
    }
    return fingerprint.substring(0, dash);
  }
}
