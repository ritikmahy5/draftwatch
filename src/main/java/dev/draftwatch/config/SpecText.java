package dev.draftwatch.config;

import java.math.BigDecimal;

/** Formatting shared by the spec classes' {@code describe()} methods. */
final class SpecText {
  private SpecText() {}

  /** Shortest exact decimal text of a finite double: {@code 0.95}, {@code 0}, {@code -0.02}. */
  static String number(double value) {
    return CanonicalJson.canonicalDecimal(BigDecimal.valueOf(value));
  }
}
