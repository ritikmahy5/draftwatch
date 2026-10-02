package dev.draftwatch.exec;

/** An executor could not submit an attempt; the job becomes FAILED with SUBMISSION_FAILED. */
public final class ExecutorException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public ExecutorException(String message) {
    super(message);
  }

  public ExecutorException(String message, Throwable cause) {
    super(message, cause);
  }
}
