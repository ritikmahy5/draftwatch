package dev.draftwatch.fingerprint;

import static org.junit.Assert.assertEquals;

import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class Sha256Test {
  @Test
  public void matchesFips180Vector() {
    // FIPS 180-2, Appendix B.1: SHA-256("abc").
    assertEquals(
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        Sha256.hex("abc".getBytes(StandardCharsets.US_ASCII)));
  }

  @Test
  public void hexIsLowercaseTwoDigitsPerByte() {
    assertEquals("00ff7f80", Sha256.toHex(new byte[] {0, (byte) 0xff, 0x7f, (byte) 0x80}));
  }
}
