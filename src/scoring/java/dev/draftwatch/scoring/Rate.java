package dev.draftwatch.scoring;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * A fraction {@code flagged / total}. With an empty denominator it has no value, and says why;
 * it is never reported as 0 or NaN.
 */
public final class Rate {
  private final int flagged;
  private final int total;
  private final Optional<String> undefinedReason;

  private Rate(int flagged, int total, Optional<String> undefinedReason) {
    this.flagged = flagged;
    this.total = total;
    this.undefinedReason = undefinedReason;
  }

  /** {@code flagged / total}, or undefined for {@code whyIfEmpty} when {@code total} is 0. */
  public static Rate of(int flagged, int total, String whyIfEmpty) {
    Objects.requireNonNull(whyIfEmpty, "whyIfEmpty");
    if (flagged < 0 || flagged > total) {
      throw new IllegalArgumentException("flagged " + flagged + " of " + total);
    }
    return new Rate(flagged, total, total == 0 ? Optional.of(whyIfEmpty) : Optional.empty());
  }

  public OptionalDouble value() {
    return total == 0 ? OptionalDouble.empty() : OptionalDouble.of((double) flagged / total);
  }

  public int flagged() {
    return flagged;
  }

  public int total() {
    return total;
  }

  public Optional<String> undefinedReason() {
    return undefinedReason;
  }

  @Override
  public String toString() {
    return flagged + "/" + total + undefinedReason.map(r -> " (" + r + ")").orElse("");
  }
}
