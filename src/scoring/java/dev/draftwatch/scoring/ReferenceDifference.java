package dev.draftwatch.scoring;

/**
 * Δ_ref of one checkpoint on one reference probe: its {@code alpha} minus checkpoint 0's on all
 * the probe's prompts, with the paired-bootstrap interval [L, U] and the label they give.
 */
public final class ReferenceDifference {
  private final double delta;
  private final double lower;
  private final double upper;
  private final Label label;

  private ReferenceDifference(double delta, double lower, double upper) {
    this.delta = delta;
    this.lower = lower;
    this.upper = upper;
    this.label = GroundTruth.label(delta, lower, upper);
  }

  public static ReferenceDifference of(double delta, double lower, double upper) {
    return new ReferenceDifference(delta, lower, upper);
  }

  public double delta() {
    return delta;
  }

  public double lower() {
    return lower;
  }

  public double upper() {
    return upper;
  }

  public Label label() {
    return label;
  }

  @Override
  public String toString() {
    return label + " delta " + delta + " [" + lower + ", " + upper + "]";
  }
}
