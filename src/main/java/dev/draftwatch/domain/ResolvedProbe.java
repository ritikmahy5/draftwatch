package dev.draftwatch.domain;

import java.util.Objects;

/**
 * A probe together with the content-derived facts its identity depends on: the draft's
 * fingerprint, the prompt set, and the resulting probe hash.
 */
public final class ResolvedProbe {
  private final Probe probe;
  private final String draftFingerprint;
  private final PromptSet promptSet;
  private final String hash;

  private ResolvedProbe(Probe probe, String draftFingerprint, PromptSet promptSet, String hash) {
    this.probe = probe;
    this.draftFingerprint = draftFingerprint;
    this.promptSet = promptSet;
    this.hash = hash;
  }

  /**
   * Creates a resolved probe.
   *
   * @throws IllegalArgumentException if {@code promptSet} is not for the probe's prompt file
   */
  public static ResolvedProbe of(
      Probe probe, String draftFingerprint, PromptSet promptSet, String hash) {
    Require.nonNull(probe, "probe");
    Require.nonNull(promptSet, "prompt set");
    if (!promptSet.path().equals(probe.promptsPath())) {
      throw new IllegalArgumentException(
          "prompt set " + promptSet.path() + " is not the probe's file " + probe.promptsPath());
    }
    return new ResolvedProbe(
        probe,
        Require.nonBlank(draftFingerprint, "draft fingerprint"),
        promptSet,
        Require.sha256Hex(hash, "probe hash"));
  }

  public Probe probe() {
    return probe;
  }

  public String draftFingerprint() {
    return draftFingerprint;
  }

  public PromptSet promptSet() {
    return promptSet;
  }

  /** Lowercase hex SHA-256 identifying this probe for comparability. */
  public String hash() {
    return hash;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof ResolvedProbe)) {
      return false;
    }
    ResolvedProbe that = (ResolvedProbe) o;
    return probe.equals(that.probe)
        && draftFingerprint.equals(that.draftFingerprint)
        && promptSet.equals(that.promptSet)
        && hash.equals(that.hash);
  }

  @Override
  public int hashCode() {
    return Objects.hash(probe, draftFingerprint, promptSet, hash);
  }

  @Override
  public String toString() {
    return "ResolvedProbe{" + probe.id() + ", hash=" + hash + "}";
  }
}
