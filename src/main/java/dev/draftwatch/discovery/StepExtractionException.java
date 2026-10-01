package dev.draftwatch.discovery;

import java.nio.file.Path;

/** A checkpoint's training step could not be determined; the message names the directory. */
public final class StepExtractionException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final transient Path checkpointDir;

  public StepExtractionException(Path checkpointDir, String message) {
    super(checkpointDir + ": " + message);
    this.checkpointDir = checkpointDir;
  }

  public StepExtractionException(Path checkpointDir, String message, Throwable cause) {
    super(checkpointDir + ": " + message, cause);
    this.checkpointDir = checkpointDir;
  }

  public Path checkpointDir() {
    return checkpointDir;
  }
}
