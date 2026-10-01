package dev.draftwatch.domain;

import java.util.Objects;

/**
 * Pooled counts for one draft position: an element of a report's {@code position_counts} list.
 * Positions are 1-indexed; position 1 is the first draft token of a step.
 */
public final class PositionCount {
  private final int position;
  private final long eligible;
  private final long accepted;

  private PositionCount(int position, long eligible, long accepted) {
    this.position = position;
    this.eligible = eligible;
    this.accepted = accepted;
  }

  public static PositionCount of(int position, long eligible, long accepted) {
    return new PositionCount(position, eligible, accepted);
  }

  public int position() {
    return position;
  }

  /** Steps where this position was proposed and every earlier position was accepted. */
  public long eligible() {
    return eligible;
  }

  public long accepted() {
    return accepted;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof PositionCount)) {
      return false;
    }
    PositionCount that = (PositionCount) o;
    return position == that.position && eligible == that.eligible && accepted == that.accepted;
  }

  @Override
  public int hashCode() {
    return Objects.hash(position, eligible, accepted);
  }

  @Override
  public String toString() {
    return "PositionCount{" + position + ": " + accepted + "/" + eligible + "}";
  }
}
