package dev.draftwatch.scoring;

/** The stored evaluation data cannot be scored; the message names the file, job, or field. */
public final class DataException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public DataException(String message) {
    super(message);
  }

  public DataException(String message, Throwable cause) {
    super(message, cause);
  }
}
