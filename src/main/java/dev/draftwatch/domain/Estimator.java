package dev.draftwatch.domain;

/**
 * How per-prompt counts are aggregated into {@code alpha} and {@code tau}
 * (MEASUREMENT_CONTRACT.md, "Estimator"). Results with different estimators are never compared.
 */
public enum Estimator implements WireNamed {
  /** Pool all verification steps across prompts and compute each ratio once. */
  TOKEN_WEIGHTED("token_weighted"),
  /** Compute the metric per prompt, then take the unweighted mean over prompts. */
  SIMPLE_MEAN("simple_mean");

  private final String wireName;

  Estimator(String wireName) {
    this.wireName = wireName;
  }

  @Override
  public String wireName() {
    return wireName;
  }
}
