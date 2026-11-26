package dev.draftwatch.scoring;

/** Run 2's aggregate {@code alpha} minus run 1's at one step where both have a result. */
public final class DriftPoint {
  private final long step;
  private final double run2MinusRun1Alpha;

  DriftPoint(long step, double run2MinusRun1Alpha) {
    this.step = step;
    this.run2MinusRun1Alpha = run2MinusRun1Alpha;
  }

  public long step() {
    return step;
  }

  public double run2MinusRun1Alpha() {
    return run2MinusRun1Alpha;
  }
}
