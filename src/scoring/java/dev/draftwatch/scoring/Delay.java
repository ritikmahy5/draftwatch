package dev.draftwatch.scoring;

import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalLong;

/**
 * Detection delay on run 2 (docs/EVALUATION.md, "Metrics"): from the onset, the first branch
 * checkpoint labeled REGRESSED, to each subset's first flag at or after it, in training steps. A
 * subset that never flags there has an infinite delay. The median is the lower middle of the
 * sorted delays (index {@code (count − 1) / 2}), so it is always one subset's delay, an exact
 * number of steps; it is empty when that delay is infinite. Delay is not scored when no branch
 * checkpoint is REGRESSED or when the control failed (a trunk checkpoint at steps 100–600 is
 * REGRESSED); the fractions are then empty.
 */
public final class Delay {
  /** Whether the delay was scored, and if not, why. */
  public enum Status {
    SCORED,
    NO_REGRESSED_BRANCH_CHECKPOINT,
    CONTROL_FAILED
  }

  private final Status status;
  private final OptionalLong onsetStep;
  private final OptionalLong medianSteps;
  private final OptionalDouble missFraction;
  private final OptionalDouble earlyFraction;
  private final OptionalDouble falseAlarmBeforeOnsetFraction;
  private final List<Long> controlFailedSteps;

  Delay(
      Status status,
      OptionalLong onsetStep,
      OptionalLong medianSteps,
      OptionalDouble missFraction,
      OptionalDouble earlyFraction,
      OptionalDouble falseAlarmBeforeOnsetFraction,
      List<Long> controlFailedSteps) {
    this.status = status;
    this.onsetStep = onsetStep;
    this.medianSteps = medianSteps;
    this.missFraction = missFraction;
    this.earlyFraction = earlyFraction;
    this.falseAlarmBeforeOnsetFraction = falseAlarmBeforeOnsetFraction;
    this.controlFailedSteps = List.copyOf(controlFailedSteps);
  }

  public Status status() {
    return status;
  }

  public OptionalLong onsetStep() {
    return onsetStep;
  }

  /** Empty when not scored, or when more than half the subsets miss. */
  public OptionalLong medianSteps() {
    return medianSteps;
  }

  public OptionalDouble missFraction() {
    return missFraction;
  }

  /** Subsets with a flag on an AMBIGUOUS branch checkpoint before the onset. */
  public OptionalDouble earlyFraction() {
    return earlyFraction;
  }

  /** Subsets with a flag on a NOT_REGRESSED branch checkpoint before the onset. */
  public OptionalDouble falseAlarmBeforeOnsetFraction() {
    return falseAlarmBeforeOnsetFraction;
  }

  /** The REGRESSED trunk checkpoints at steps 100–600, if the control failed. */
  public List<Long> controlFailedSteps() {
    return controlFailedSteps;
  }
}
