package dev.draftwatch.exec;

import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Random;

/**
 * Ids like {@code j20261001T120000Z-3fa9c2d1}: the UTC creation time, so ids sort by creation,
 * plus 32 random bits, so two ids created in the same second differ.
 */
public final class TimestampJobIds implements JobIds {
  private static final DateTimeFormatter FORMAT =
      DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

  private final Clock clock;
  private final Random random;

  public TimestampJobIds(Clock clock, Random random) {
    this.clock = Objects.requireNonNull(clock, "clock");
    this.random = Objects.requireNonNull(random, "random");
  }

  @Override
  public String next() {
    return "j" + FORMAT.format(clock.instant()) + "-" + String.format("%08x", random.nextInt());
  }
}
