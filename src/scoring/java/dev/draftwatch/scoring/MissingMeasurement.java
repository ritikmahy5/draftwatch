package dev.draftwatch.scoring;

import java.util.Objects;

/** An expected checkpoint of a probe that has no stored result. */
public final class MissingMeasurement {
  private final String target;
  private final String probeId;
  private final long step;

  MissingMeasurement(String target, String probeId, long step) {
    this.target = Objects.requireNonNull(target, "target");
    this.probeId = Objects.requireNonNull(probeId, "probeId");
    this.step = step;
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

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof MissingMeasurement)) {
      return false;
    }
    MissingMeasurement other = (MissingMeasurement) o;
    return target.equals(other.target) && probeId.equals(other.probeId) && step == other.step;
  }

  @Override
  public int hashCode() {
    return Objects.hash(target, probeId, step);
  }

  @Override
  public String toString() {
    return target + " " + probeId + " step " + step;
  }
}
