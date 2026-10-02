package dev.draftwatch.stats;

/** A statistic had nothing to average (for example, no draft tokens proposed). */
public final class UndefinedStatisticException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public UndefinedStatisticException(String message) {
    super(message);
  }
}
