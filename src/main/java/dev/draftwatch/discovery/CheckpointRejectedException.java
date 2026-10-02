package dev.draftwatch.discovery;

import java.nio.file.Path;

/** A directory is not (yet) a measurable checkpoint; the message names it and says why. */
public final class CheckpointRejectedException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final transient Path dir;
  private final boolean incomplete;

  public CheckpointRejectedException(Path dir, String reason) {
    this(dir, reason, false, null);
  }

  public CheckpointRejectedException(Path dir, String reason, Throwable cause) {
    this(dir, reason, false, cause);
  }

  private CheckpointRejectedException(
      Path dir, String reason, boolean incomplete, Throwable cause) {
    super(dir + ": " + reason, cause);
    this.dir = dir;
    this.incomplete = incomplete;
  }

  /** The directory is not completely written yet: expected while training saves it. */
  public static CheckpointRejectedException incomplete(Path dir, String reason) {
    return new CheckpointRejectedException(dir, "not complete: " + reason, true, null);
  }

  public Path dir() {
    return dir;
  }

  /** True if the directory may still become a checkpoint; false if it is invalid as it is. */
  public boolean isIncomplete() {
    return incomplete;
  }
}
