package dev.draftwatch.config;

import java.util.Objects;

/**
 * {@code completion}: how a checkpoint directory is judged completely written (SPEC.md F1).
 * Either a marker file the training script writes last, or a settle time during which no
 * file's size or modification time changes.
 */
public final class CompletionSpec {
  /** SPEC.md F1: the default policy is {@code settle_seconds: 120}. */
  public static final int DEFAULT_SETTLE_SECONDS = 120;

  /** The two completion policies. */
  public enum Kind {
    MARKER,
    SETTLE_SECONDS
  }

  private final Kind kind;
  private final String marker;
  private final int settleSeconds;

  private CompletionSpec(Kind kind, String marker, int settleSeconds) {
    this.kind = kind;
    this.marker = marker;
    this.settleSeconds = settleSeconds;
  }

  /** Complete when a file named {@code marker} exists in the checkpoint directory. */
  public static CompletionSpec marker(String marker) {
    if (marker == null || marker.trim().isEmpty()) {
      throw new IllegalArgumentException("marker must not be blank");
    }
    return new CompletionSpec(Kind.MARKER, marker, 0);
  }

  /** Complete when nothing changed across two polls at least {@code seconds} apart. */
  public static CompletionSpec settleSeconds(int seconds) {
    if (seconds <= 0) {
      throw new IllegalArgumentException("settle_seconds must be > 0, was " + seconds);
    }
    return new CompletionSpec(Kind.SETTLE_SECONDS, null, seconds);
  }

  public static CompletionSpec defaultSpec() {
    return settleSeconds(DEFAULT_SETTLE_SECONDS);
  }

  public Kind kind() {
    return kind;
  }

  /**
   * The marker file name.
   *
   * @throws IllegalStateException if this is a settle-time policy
   */
  public String marker() {
    if (kind != Kind.MARKER) {
      throw new IllegalStateException("not a marker policy");
    }
    return marker;
  }

  /**
   * The settle time in seconds.
   *
   * @throws IllegalStateException if this is a marker policy
   */
  public int settleSecondsValue() {
    if (kind != Kind.SETTLE_SECONDS) {
      throw new IllegalStateException("not a settle-time policy");
    }
    return settleSeconds;
  }

  /** Config-like text, for {@code draftwatch validate}. */
  public String describe() {
    return kind == Kind.MARKER ? "marker " + marker : "settle_seconds " + settleSeconds;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof CompletionSpec)) {
      return false;
    }
    CompletionSpec that = (CompletionSpec) o;
    return kind == that.kind
        && Objects.equals(marker, that.marker)
        && settleSeconds == that.settleSeconds;
  }

  @Override
  public int hashCode() {
    return Objects.hash(kind, marker, settleSeconds);
  }

  @Override
  public String toString() {
    return "CompletionSpec{" + describe() + "}";
  }
}
