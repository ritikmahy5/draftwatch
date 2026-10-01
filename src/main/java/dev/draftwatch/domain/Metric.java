package dev.draftwatch.domain;

/**
 * A metric that detectors may read (SPEC.md F5). Positional acceptance is reported but never
 * used by detectors (MEASUREMENT_CONTRACT.md), so it is not a {@code Metric}.
 */
public enum Metric implements WireNamed {
  /** Acceptance rate: an empirical fraction, not the acceptance probability of the literature. */
  ALPHA("alpha"),
  /** Mean accepted length: tokens emitted per target forward pass. */
  TAU("tau");

  private final String wireName;

  Metric(String wireName) {
    this.wireName = wireName;
  }

  @Override
  public String wireName() {
    return wireName;
  }
}
