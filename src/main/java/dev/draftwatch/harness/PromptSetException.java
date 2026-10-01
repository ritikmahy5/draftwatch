package dev.draftwatch.harness;

import java.nio.file.Path;

/** A prompt file violates the contract's prompt-file format; the message names file and line. */
public final class PromptSetException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final transient Path file;

  public PromptSetException(Path file, String message) {
    super(file + ": " + message);
    this.file = file;
  }

  public PromptSetException(Path file, String message, Throwable cause) {
    super(file + ": " + message, cause);
    this.file = file;
  }

  public Path file() {
    return file;
  }
}
