package dev.draftwatch.domain;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * One saved state of a target: where it is, its training step, its weight fingerprint, its
 * type, and whether it is final.
 *
 * <p>Built with a {@link Builder} because it has seven fields and the same base-model rule as
 * {@link Target}.
 */
public final class Checkpoint {
  private final String targetName;
  private final Path path;
  private final long step;
  private final String fingerprint;
  private final CheckpointType type;
  private final Optional<Path> baseModel;
  private final Optional<String> baseModelFingerprint;
  private final boolean isFinal;

  private Checkpoint(Builder b) {
    this.targetName = b.targetName;
    this.path = b.path;
    this.step = b.step;
    this.fingerprint = b.fingerprint;
    this.type = b.type;
    this.baseModel = b.baseModel;
    this.baseModelFingerprint = b.baseModelFingerprint;
    this.isFinal = b.isFinal;
  }

  public static Builder builder() {
    return new Builder();
  }

  public String targetName() {
    return targetName;
  }

  public Path path() {
    return path;
  }

  public long step() {
    return step;
  }

  /**
   * Content fingerprint of the weight files (SPEC.md F1); the checkpoint's identity. For an
   * adapter it covers the adapter and its base model (DECISIONS.md D42).
   */
  public String fingerprint() {
    return fingerprint;
  }

  public CheckpointType type() {
    return type;
  }

  /** Present exactly when {@link #type()} is {@link CheckpointType#ADAPTER}. */
  public Optional<Path> baseModel() {
    return baseModel;
  }

  /** The base model's own weight fingerprint; present exactly with {@link #baseModel()}. */
  public Optional<String> baseModelFingerprint() {
    return baseModelFingerprint;
  }

  public boolean isFinal() {
    return isFinal;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Checkpoint)) {
      return false;
    }
    Checkpoint that = (Checkpoint) o;
    return targetName.equals(that.targetName)
        && path.equals(that.path)
        && step == that.step
        && fingerprint.equals(that.fingerprint)
        && type == that.type
        && baseModel.equals(that.baseModel)
        && baseModelFingerprint.equals(that.baseModelFingerprint)
        && isFinal == that.isFinal;
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        targetName, path, step, fingerprint, type, baseModel, baseModelFingerprint, isFinal);
  }

  @Override
  public String toString() {
    return "Checkpoint{" + targetName + " step " + step + ", " + path + "}";
  }

  /** Builder for {@link Checkpoint}; {@link #build()} rejects missing or inconsistent fields. */
  public static final class Builder {
    private String targetName;
    private Path path;
    private long step = -1;
    private String fingerprint;
    private CheckpointType type;
    private Optional<Path> baseModel = Optional.empty();
    private Optional<String> baseModelFingerprint = Optional.empty();
    private boolean isFinal;

    private Builder() {}

    public Builder targetName(String targetName) {
      this.targetName = targetName;
      return this;
    }

    public Builder path(Path path) {
      this.path = path;
      return this;
    }

    public Builder step(long step) {
      this.step = step;
      return this;
    }

    public Builder fingerprint(String fingerprint) {
      this.fingerprint = fingerprint;
      return this;
    }

    public Builder type(CheckpointType type) {
      this.type = type;
      return this;
    }

    /** The base model an adapter is merged into, and that base model's weight fingerprint. */
    public Builder baseModel(Path baseModel, String baseModelFingerprint) {
      this.baseModel = Optional.of(Require.nonNull(baseModel, "base model"));
      this.baseModelFingerprint =
          Optional.of(Require.nonBlank(baseModelFingerprint, "base model fingerprint"));
      return this;
    }

    public Builder isFinal(boolean isFinal) {
      this.isFinal = isFinal;
      return this;
    }

    /**
     * Builds the checkpoint.
     *
     * @throws IllegalArgumentException if a field is missing or invalid (including an unset
     *     step), or if the base model does not match the checkpoint type
     */
    public Checkpoint build() {
      Names.require(targetName, "target name");
      Require.nonNull(path, "checkpoint path");
      Require.nonNegative(step, "step");
      Require.nonBlank(fingerprint, "fingerprint");
      Require.nonNull(type, "checkpoint type");
      boolean adapter = type == CheckpointType.ADAPTER;
      if (adapter != baseModel.isPresent()) {
        throw new IllegalArgumentException(
            adapter
                ? "base model is required for an adapter checkpoint"
                : "base model is only valid for an adapter checkpoint");
      }
      return new Checkpoint(this);
    }
  }
}
