package dev.draftwatch.domain;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The model being trained: a name, where its checkpoints appear, and how to read them.
 *
 * <p>Built with a {@link Builder} because it has several fields, some with defaults from
 * SPEC.md F1, and one cross-field rule: an adapter target has a base model, a full one does not.
 */
public final class Target {
  /** SPEC.md F1: step from the directory name when there is no {@code trainer_state.json}. */
  public static final String DEFAULT_STEP_REGEX = "checkpoint-(\\d+)$";

  /** SPEC.md F1: a checkpoint is final if a file with this name exists in its directory. */
  public static final String DEFAULT_FINAL_MARKER = "FINAL";

  private final String name;
  private final List<Path> checkpointDirs;
  private final CheckpointType checkpointType;
  private final Optional<Path> baseModel;
  private final Pattern stepRegex;
  private final String finalMarker;

  private Target(Builder b, List<Path> checkpointDirs) {
    this.name = b.name;
    this.checkpointDirs = checkpointDirs;
    this.checkpointType = b.checkpointType;
    this.baseModel = b.baseModel;
    this.stepRegex = b.stepRegex;
    this.finalMarker = b.finalMarker;
  }

  public static Builder builder() {
    return new Builder();
  }

  public String name() {
    return name;
  }

  /** Unmodifiable, in configured order. */
  public List<Path> checkpointDirs() {
    return checkpointDirs;
  }

  public CheckpointType checkpointType() {
    return checkpointType;
  }

  /** Present exactly when {@link #checkpointType()} is {@link CheckpointType#ADAPTER}. */
  public Optional<Path> baseModel() {
    return baseModel;
  }

  /** Has at least one capture group; group 1 is the step. */
  public Pattern stepRegex() {
    return stepRegex;
  }

  public String finalMarker() {
    return finalMarker;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Target)) {
      return false;
    }
    Target that = (Target) o;
    return name.equals(that.name)
        && checkpointDirs.equals(that.checkpointDirs)
        && checkpointType == that.checkpointType
        && baseModel.equals(that.baseModel)
        && stepRegex.pattern().equals(that.stepRegex.pattern())
        && finalMarker.equals(that.finalMarker);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        name, checkpointDirs, checkpointType, baseModel, stepRegex.pattern(), finalMarker);
  }

  @Override
  public String toString() {
    return "Target{" + name + ", " + checkpointType.wireName() + "}";
  }

  /** Builder for {@link Target}; {@link #build()} rejects missing or inconsistent fields. */
  public static final class Builder {
    private String name;
    private List<Path> checkpointDirs;
    private CheckpointType checkpointType;
    private Optional<Path> baseModel = Optional.empty();
    private Pattern stepRegex = Pattern.compile(DEFAULT_STEP_REGEX);
    private String finalMarker = DEFAULT_FINAL_MARKER;

    private Builder() {}

    public Builder name(String name) {
      this.name = name;
      return this;
    }

    public Builder checkpointDirs(List<Path> checkpointDirs) {
      this.checkpointDirs = checkpointDirs;
      return this;
    }

    public Builder checkpointType(CheckpointType checkpointType) {
      this.checkpointType = checkpointType;
      return this;
    }

    public Builder baseModel(Path baseModel) {
      this.baseModel = Optional.of(baseModel);
      return this;
    }

    public Builder stepRegex(Pattern stepRegex) {
      this.stepRegex = stepRegex;
      return this;
    }

    public Builder finalMarker(String finalMarker) {
      this.finalMarker = finalMarker;
      return this;
    }

    /**
     * Builds the target.
     *
     * @throws IllegalArgumentException if a field is missing or invalid, or if the base model
     *     is absent for an adapter target or present for a full one
     */
    public Target build() {
      Names.require(name, "target name");
      List<Path> dirs = Require.nonEmptyCopy(checkpointDirs, "checkpoint_dirs");
      Require.nonNull(checkpointType, "checkpoint_type");
      Require.nonNull(stepRegex, "step_regex");
      if (stepRegex.matcher("").groupCount() < 1) {
        throw new IllegalArgumentException("step_regex must have a capture group");
      }
      Require.nonBlank(finalMarker, "final_marker");
      boolean adapter = checkpointType == CheckpointType.ADAPTER;
      if (adapter && baseModel.isEmpty()) {
        throw new IllegalArgumentException("base_model is required for an adapter target");
      }
      if (!adapter && baseModel.isPresent()) {
        throw new IllegalArgumentException("base_model is only valid for an adapter target");
      }
      return new Target(this, dirs);
    }
  }
}
