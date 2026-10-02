package dev.draftwatch.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.time.Duration;
import org.junit.Test;

public class DurationsTest {
  @Test
  public void parsesSecondsMinutesAndHours() {
    assertEquals(Duration.ofSeconds(90), Durations.parse("90"));
    assertEquals(Duration.ofSeconds(90), Durations.parse("90s"));
    assertEquals(Duration.ofMinutes(15), Durations.parse("15m"));
    assertEquals(Duration.ofHours(2), Durations.parse("2h"));
  }

  @Test
  public void rejectsZeroNegativeAndOtherText() {
    String[] bad = {"0", "0m", "-5s", "5 m", "1d", "", "1.5h", "99999999999999999999"};
    for (String text : bad) {
      try {
        Durations.parse(text);
        fail("accepted '" + text + "'");
      } catch (IllegalArgumentException e) {
        assertTrue(e.getMessage(), e.getMessage().contains("'" + text + "'"));
      }
    }
  }
}
