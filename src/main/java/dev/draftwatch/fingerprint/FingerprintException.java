package dev.draftwatch.fingerprint;

import java.nio.file.Path;

/** A directory could not be fingerprinted; the message names the directory or file. */
public final class FingerprintException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final transient Path path;

  public FingerprintException(Path path, String message) {
    super(path + ": " + message);
    this.path = path;
  }

  public FingerprintException(Path path, String message, Throwable cause) {
    super(path + ": " + message, cause);
    this.path = path;
  }

  /** The directory or file that could not be fingerprinted. */
  public Path path() {
    return path;
  }
}
