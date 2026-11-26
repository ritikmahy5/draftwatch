package dev.draftwatch.scoring;

import java.util.Objects;

/** One compared checkpoint of a reference probe with its ground truth. */
public final class CheckpointRow {
  private final Run run;
  private final long step;
  private final ReferenceDifference reference;

  private CheckpointRow(Run run, long step, ReferenceDifference reference) {
    this.run = Objects.requireNonNull(run, "run");
    this.step = step;
    this.reference = Objects.requireNonNull(reference, "reference");
  }

  public static CheckpointRow of(Run run, long step, ReferenceDifference reference) {
    return new CheckpointRow(run, step, reference);
  }

  public Run run() {
    return run;
  }

  public long step() {
    return step;
  }

  public ReferenceDifference reference() {
    return reference;
  }

  public Label label() {
    return reference.label();
  }

  @Override
  public String toString() {
    return run.suffix() + " step " + step + " " + reference;
  }
}
