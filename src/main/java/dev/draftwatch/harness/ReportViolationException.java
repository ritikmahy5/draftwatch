package dev.draftwatch.harness;

import java.util.Objects;

/** A harness report broke a contract rule; the message starts with the rule id. */
public final class ReportViolationException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final ReportRule rule;

  public ReportViolationException(ReportRule rule, String detail) {
    super("report rule " + Objects.requireNonNull(rule, "rule").wireName() + ": " + detail);
    this.rule = rule;
  }

  public ReportViolationException(ReportRule rule, String detail, Throwable cause) {
    super("report rule " + Objects.requireNonNull(rule, "rule").wireName() + ": " + detail, cause);
    this.rule = rule;
  }

  public ReportRule rule() {
    return rule;
  }
}
