package dev.draftwatch.harness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import dev.draftwatch.stats.MetricCalculator;
import dev.draftwatch.testing.ReportScenario;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

/**
 * One negative test per contract rule and scenario: the fake harness breaks exactly that rule
 * ({@code DRAFTWATCH_FAKE_CORRUPT}), and the parser must reject the report naming exactly that
 * rule. A rule the fake does not know makes it exit 2, failing the test.
 */
@RunWith(Parameterized.class)
public class ReportCorruptionTest {
  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private final ReportRule rule;
  private final Function<Path, ReportScenario> scenario;

  public ReportCorruptionTest(
      String name, ReportRule rule, Function<Path, ReportScenario> scenario) {
    this.rule = rule;
    this.scenario = scenario;
  }

  @Parameters(name = "{0}")
  public static Collection<Object[]> cases() {
    List<Object[]> cases = new ArrayList<>();
    for (ReportRule rule : ReportRule.values()) {
      Function<Path, ReportScenario> greedy = ReportScenario::greedy;
      Function<Path, ReportScenario> sampled = ReportScenario::sampledAdapter;
      cases.add(new Object[] {rule.wireName() + " (greedy)", rule, greedy});
      cases.add(new Object[] {rule.wireName() + " (sampled adapter)", rule, sampled});
    }
    return cases;
  }

  @Test
  public void corruptedReportIsRejectedNamingExactlyThatRule() {
    ReportScenario s = scenario.apply(tmp.getRoot().toPath());
    int exit = s.run(Map.of("DRAFTWATCH_FAKE_CORRUPT", rule.wireName()));
    assertEquals("fake harness exit code", 0, exit);
    try {
      new ReportParser(new MetricCalculator()).parse(s.reportPath(), s.expected());
      fail("report corrupted for " + rule.wireName() + " was accepted");
    } catch (ReportViolationException e) {
      assertEquals(e.getMessage(), rule, e.rule());
      String prefix = "report rule " + rule.wireName() + ": ";
      assertTrue(e.getMessage(), e.getMessage().startsWith(prefix));
    }
  }
}
