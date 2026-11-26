package dev.draftwatch.scoring;

import java.nio.file.Path;

/** The archive cannot be exported as it is; the message names the offending file. */
public final class ExportException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public ExportException(Path file, String problem) {
    super(file + ": " + problem);
  }

  public ExportException(Path file, String problem, Throwable cause) {
    super(file + ": " + problem, cause);
  }
}
