package dev.draftwatch.store;

import java.nio.file.Path;

/** A state-directory file could not be read or written; the message names the file. */
public final class StoreException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public StoreException(Path file, String message) {
    super(file + ": " + message);
  }

  public StoreException(Path file, String message, Throwable cause) {
    super(file + ": " + message, cause);
  }
}
