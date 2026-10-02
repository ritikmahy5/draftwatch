package dev.draftwatch.app;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses CLI durations: {@code 90} or {@code 90s}, {@code 15m}, {@code 2h}. */
final class Durations {
  private static final Pattern FORMAT = Pattern.compile("(\\d+)([smh]?)");

  private Durations() {}

  /** @throws IllegalArgumentException naming the text if it is not a positive duration */
  static Duration parse(String text) {
    Matcher m = FORMAT.matcher(text);
    if (!m.matches()) {
      throw new IllegalArgumentException(
          "'" + text + "' is not a duration such as 90s, 15m, or 2h");
    }
    long amount;
    try {
      amount = Long.parseLong(m.group(1));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("'" + text + "' is too large", e);
    }
    if (amount == 0) {
      throw new IllegalArgumentException("'" + text + "' must be greater than zero");
    }
    switch (m.group(2)) {
      case "m":
        return Duration.ofMinutes(amount);
      case "h":
        return Duration.ofHours(amount);
      default:
        return Duration.ofSeconds(amount);
    }
  }
}
