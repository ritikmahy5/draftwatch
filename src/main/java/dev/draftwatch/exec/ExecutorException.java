package dev.draftwatch.exec;

/**
 * An executor could not do what was asked. From {@code submit}, the job becomes FAILED with
 * SUBMISSION_FAILED; from {@code status} or {@code cancel}, the job is unchanged and the caller
 * reports the error (DECISIONS.md D61).
 */
public final class ExecutorException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public ExecutorException(String message) {
    super(message);
  }

  public ExecutorException(String message, Throwable cause) {
    super(message, cause);
  }
}
