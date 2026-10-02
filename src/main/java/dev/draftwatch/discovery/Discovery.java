package dev.draftwatch.discovery;

import dev.draftwatch.domain.Checkpoint;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** The result of one poll: complete checkpoints in step order, and every skipped path. */
public final class Discovery {
  /** Why a path was skipped. */
  public enum SkipKind {
    /** A configured checkpoint directory does not exist (yet). */
    MISSING_DIRECTORY,
    /** A checkpoint is still being written; expected, not an error. */
    INCOMPLETE,
    /** A complete directory is not a valid checkpoint (no step, no weights): an error. */
    REJECTED
  }

  /** One skipped path. */
  public static final class Skipped {
    private final Path path;
    private final SkipKind kind;
    private final String reason;

    public Skipped(Path path, SkipKind kind, String reason) {
      this.path = Objects.requireNonNull(path, "path");
      this.kind = Objects.requireNonNull(kind, "kind");
      this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Path path() {
      return path;
    }

    public SkipKind kind() {
      return kind;
    }

    /** Why, starting with the path. */
    public String reason() {
      return reason;
    }

    @Override
    public String toString() {
      return kind + " " + path + ": " + reason;
    }
  }

  private final List<Checkpoint> checkpoints;
  private final List<Skipped> skipped;

  public Discovery(List<Checkpoint> checkpoints, List<Skipped> skipped) {
    this.checkpoints = List.copyOf(checkpoints);
    this.skipped = List.copyOf(skipped);
  }

  public List<Checkpoint> checkpoints() {
    return checkpoints;
  }

  public List<Skipped> skipped() {
    return skipped;
  }
}
