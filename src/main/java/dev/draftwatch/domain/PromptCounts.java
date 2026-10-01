package dev.draftwatch.domain;

import java.util.Objects;

/**
 * Verifier-decision counts for one prompt under one seed: an element of a report's
 * {@code per_prompt} list (MEASUREMENT_CONTRACT.md). Contract rules on these counts are checked
 * by the report parser, which names the violated rule; this class only holds them.
 */
public final class PromptCounts {
  private final int promptIndex;
  private final long steps;
  private final long proposed;
  private final long accepted;

  private PromptCounts(int promptIndex, long steps, long proposed, long accepted) {
    this.promptIndex = promptIndex;
    this.steps = steps;
    this.proposed = proposed;
    this.accepted = accepted;
  }

  public static PromptCounts of(int promptIndex, long steps, long proposed, long accepted) {
    return new PromptCounts(promptIndex, steps, proposed, accepted);
  }

  /** 0-based index of the prompt's non-empty line in the prompt file. */
  public int promptIndex() {
    return promptIndex;
  }

  /** Verification steps (target forward passes). */
  public long steps() {
    return steps;
  }

  /** Draft tokens proposed, including those after the first rejection in a step. */
  public long proposed() {
    return proposed;
  }

  /** Draft tokens accepted by the verifier. */
  public long accepted() {
    return accepted;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof PromptCounts)) {
      return false;
    }
    PromptCounts that = (PromptCounts) o;
    return promptIndex == that.promptIndex
        && steps == that.steps
        && proposed == that.proposed
        && accepted == that.accepted;
  }

  @Override
  public int hashCode() {
    return Objects.hash(promptIndex, steps, proposed, accepted);
  }

  @Override
  public String toString() {
    return "PromptCounts{" + promptIndex + ": " + steps + "/" + proposed + "/" + accepted + "}";
  }
}
