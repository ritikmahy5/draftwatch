package dev.draftwatch.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public class CommandUsageTest {
  @Test
  public void synopsisOmitsEmptyArguments() {
    assertEquals("status", CommandUsage.of("status", "", "s").synopsis());
  }

  @Test
  public void synopsisJoinsNameAndArguments() {
    assertEquals("submit <t>", CommandUsage.of("submit", "<t>", "s").synopsis());
  }

  @Test
  public void blankNameIsRejectedNamingTheField() {
    assertRejected(" ", "s", "name");
  }

  @Test
  public void nullNameIsRejectedNamingTheField() {
    assertRejected(null, "s", "name");
  }

  @Test
  public void blankSummaryIsRejectedNamingTheField() {
    assertRejected("status", "", "summary");
  }

  @Test(expected = NullPointerException.class)
  public void nullArgumentsIsRejected() {
    CommandUsage.of("status", null, "s");
  }

  private static void assertRejected(String name, String summary, String field) {
    try {
      CommandUsage.of(name, "", summary);
      fail("expected IllegalArgumentException");
    } catch (IllegalArgumentException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("CommandUsage." + field));
    }
  }
}
