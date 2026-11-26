package dev.draftwatch.scoring;

import java.util.Arrays;

/**
 * One probe subset: {@code size} distinct prompt indices of the reference pool, ascending. The
 * ascending order is the subset's fixed prompt order, which the paired bootstrap pairs by.
 */
public final class Subset {
  private final int size;
  private final int ordinal;
  private final int[] indices;

  Subset(int ordinal, int[] indices) {
    this.size = indices.length;
    this.ordinal = ordinal;
    this.indices = indices.clone();
  }

  public int size() {
    return size;
  }

  /** The subset's position in its draw, 0 … count−1. */
  public int ordinal() {
    return ordinal;
  }

  /** The prompt indices, ascending; a copy. */
  public int[] indices() {
    return indices.clone();
  }

  @Override
  public String toString() {
    return "subset " + ordinal + " of size " + size + " " + Arrays.toString(indices);
  }
}
