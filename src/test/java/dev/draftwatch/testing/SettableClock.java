package dev.draftwatch.testing;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A UTC clock that stays where a test puts it. */
public final class SettableClock extends Clock {
  private Instant now;

  public SettableClock(Instant start) {
    this.now = start;
  }

  public void set(Instant instant) {
    now = instant;
  }

  @Override
  public Instant instant() {
    return now;
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    throw new UnsupportedOperationException("SettableClock is UTC only");
  }
}
