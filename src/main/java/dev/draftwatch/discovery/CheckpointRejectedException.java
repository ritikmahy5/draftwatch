package dev.draftwatch.discovery;

import java.nio.file.Path;

/** A directory is not (yet) a measurable checkpoint; the message names it and says why. */
public final class CheckpointRejectedException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final transient Path dir;

  public CheckpointRejectedException(Path dir, String reason) {
    super(dir + ": " + reason);
    this.dir = dir;
  }

  public CheckpointRejectedException(Path dir, String reason, Throwable cause) {
    super(dir + ": " + reason, cause);
    this.dir = dir;
  }

  public Path dir() {
    return dir;
  }
}
