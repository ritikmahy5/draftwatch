package dev.draftwatch.action;

/** A regression action did not complete; the message says which part failed. */
public final class ActionFailedException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public ActionFailedException(String message) {
    super(message);
  }
}
