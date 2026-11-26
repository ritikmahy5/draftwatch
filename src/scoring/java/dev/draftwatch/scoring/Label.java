package dev.draftwatch.scoring;

/**
 * A checkpoint's ground-truth label on one reference probe (docs/EVALUATION.md, "Ground truth").
 */
public enum Label {
  REGRESSED,
  NOT_REGRESSED,
  /** Neither; reported, but excluded from the rates. */
  AMBIGUOUS
}
