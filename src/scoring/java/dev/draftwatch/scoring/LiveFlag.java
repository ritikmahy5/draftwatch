package dev.draftwatch.scoring;

/** One REGRESSION that {@code watch} logged on a live probe, beside the reference label. */
public final class LiveFlag {
  private final String target;
  private final String probeId;
  private final long step;
  private final String detector;
  private final String jobId;
  private final String referenceLabel;

  LiveFlag(
      String target, String probeId, long step, String detector, String jobId, String label) {
    this.target = target;
    this.probeId = probeId;
    this.step = step;
    this.detector = detector;
    this.jobId = jobId;
    this.referenceLabel = label;
  }

  public String target() {
    return target;
  }

  public String probeId() {
    return probeId;
  }

  public long step() {
    return step;
  }

  /** The detector as {@code watch} described it, such as {@code absolute_drop(...)}. */
  public String detector() {
    return detector;
  }

  public String jobId() {
    return jobId;
  }

  /**
   * The ground-truth label of the same family's reference probe of the same domain at this run
   * and step, or {@code "no reference"} when that checkpoint has none.
   */
  public String referenceLabel() {
    return referenceLabel;
  }
}
