package dev.draftwatch.domain;

import java.util.Objects;

/** A report's {@code hardware}: recorded for provenance, never used by detectors. */
public final class Hardware {
  private final String gpu;
  private final int count;

  private Hardware(String gpu, int count) {
    this.gpu = gpu;
    this.count = count;
  }

  public static Hardware of(String gpu, int count) {
    return new Hardware(Require.nonNull(gpu, "gpu"), count);
  }

  public String gpu() {
    return gpu;
  }

  public int count() {
    return count;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Hardware)) {
      return false;
    }
    Hardware that = (Hardware) o;
    return gpu.equals(that.gpu) && count == that.count;
  }

  @Override
  public int hashCode() {
    return Objects.hash(gpu, count);
  }

  @Override
  public String toString() {
    return "Hardware{" + count + " x " + gpu + "}";
  }
}
