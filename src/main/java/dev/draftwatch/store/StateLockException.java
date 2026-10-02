package dev.draftwatch.store;

/** The state lock could not be acquired; the message names the holder and why it was kept. */
public final class StateLockException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public StateLockException(String message) {
    super(message);
  }

  public StateLockException(String message, Throwable cause) {
    super(message, cause);
  }
}
