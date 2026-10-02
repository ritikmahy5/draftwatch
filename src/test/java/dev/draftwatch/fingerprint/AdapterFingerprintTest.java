package dev.draftwatch.fingerprint;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class AdapterFingerprintTest {
  private static final String ADAPTER = "sampled-" + "a".repeat(64);
  private static final String BASE = "sampled-" + "b".repeat(64);

  @Test
  public void combinationFollowsTheDocumentedEncoding() {
    String input = "adapter\0" + ADAPTER + "\0base\0" + BASE + "\n";
    assertEquals(
        "sampled-" + Sha256.hex(input.getBytes(StandardCharsets.UTF_8)),
        AdapterFingerprint.combine(ADAPTER, BASE));
  }

  @Test
  public void orderAndBothInputsMatter() {
    String other = "sampled-" + "c".repeat(64);
    assertNotEquals(
        AdapterFingerprint.combine(ADAPTER, BASE), AdapterFingerprint.combine(BASE, ADAPTER));
    assertNotEquals(
        AdapterFingerprint.combine(ADAPTER, BASE), AdapterFingerprint.combine(ADAPTER, other));
  }

  @Test(expected = IllegalArgumentException.class)
  public void mixedMethodsAreRejected() {
    AdapterFingerprint.combine(ADAPTER, "full-" + "b".repeat(64));
  }
}
