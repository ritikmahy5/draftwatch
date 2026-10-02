package dev.draftwatch.events;

import dev.draftwatch.domain.Baseline;
import java.time.Instant;
import java.util.Objects;

/** A target had no baseline, so its first measured checkpoint became it (DECISIONS.md D44). */
public final class BaselinePinned implements Event {
  private final Instant at;
  private final Baseline baseline;

  public BaselinePinned(Instant at, Baseline baseline) {
    this.at = Objects.requireNonNull(at, "at");
    this.baseline = Objects.requireNonNull(baseline, "baseline");
  }

  @Override
  public Instant at() {
    return at;
  }

  public Baseline baseline() {
    return baseline;
  }

  @Override
  public String toString() {
    return "BaselinePinned{" + baseline + "}";
  }
}
