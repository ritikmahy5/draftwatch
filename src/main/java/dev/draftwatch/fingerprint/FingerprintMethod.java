package dev.draftwatch.fingerprint;

import dev.draftwatch.domain.WireNamed;

/** The config values of {@code fingerprint:}; each is also the prefix of its fingerprints. */
public enum FingerprintMethod implements WireNamed {
  /** 8 × 1 MiB blocks per weight file, including the final MiB (default). */
  SAMPLED("sampled"),
  /** Every byte of every weight file. */
  FULL("full");

  private final String wireName;

  FingerprintMethod(String wireName) {
    this.wireName = wireName;
  }

  @Override
  public String wireName() {
    return wireName;
  }
}
