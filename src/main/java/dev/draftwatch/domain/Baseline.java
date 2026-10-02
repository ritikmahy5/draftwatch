package dev.draftwatch.domain;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/**
 * A target's baseline: the checkpoint every other checkpoint of the target is compared with
 * (SPEC.md, "Core concepts"). Identified by fingerprint; path and step are kept for display.
 */
public final class Baseline {
  /** How the baseline was chosen (DECISIONS.md D44). */
  public enum Source implements WireNamed {
    /** Set with {@code draftwatch baseline <target> <checkpoint>}. */
    MANUAL("manual"),
    /** Pinned automatically: the target's first measured checkpoint. */
    AUTO("auto");

    private final String wireName;

    Source(String wireName) {
      this.wireName = wireName;
    }

    @Override
    public String wireName() {
      return wireName;
    }
  }

  private final String targetName;
  private final String fingerprint;
  private final Path path;
  private final long step;
  private final Instant setAt;
  private final Source source;

  private Baseline(
      String targetName, String fingerprint, Path path, long step, Instant setAt, Source source) {
    this.targetName = targetName;
    this.fingerprint = fingerprint;
    this.path = path;
    this.step = step;
    this.setAt = setAt;
    this.source = source;
  }

  public static Baseline of(
      String targetName, String fingerprint, Path path, long step, Instant setAt, Source source) {
    return new Baseline(
        Names.require(targetName, "target name"),
        Require.nonBlank(fingerprint, "fingerprint"),
        Require.nonNull(path, "path"),
        Require.nonNegative(step, "step"),
        Require.nonNull(setAt, "set_at"),
        Require.nonNull(source, "source"));
  }

  /** The baseline for {@code checkpoint}. */
  public static Baseline of(Checkpoint checkpoint, Instant setAt, Source source) {
    return of(
        checkpoint.targetName(),
        checkpoint.fingerprint(),
        checkpoint.path(),
        checkpoint.step(),
        setAt,
        source);
  }

  public String targetName() {
    return targetName;
  }

  public String fingerprint() {
    return fingerprint;
  }

  public Path path() {
    return path;
  }

  public long step() {
    return step;
  }

  public Instant setAt() {
    return setAt;
  }

  public Source source() {
    return source;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Baseline)) {
      return false;
    }
    Baseline that = (Baseline) o;
    return targetName.equals(that.targetName)
        && fingerprint.equals(that.fingerprint)
        && path.equals(that.path)
        && step == that.step
        && setAt.equals(that.setAt)
        && source == that.source;
  }

  @Override
  public int hashCode() {
    return Objects.hash(targetName, fingerprint, path, step, setAt, source);
  }

  @Override
  public String toString() {
    return "Baseline{" + targetName + ": step " + step + ", " + path + "}";
  }
}
