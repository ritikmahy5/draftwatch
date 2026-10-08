package dev.draftwatch.exec;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import dev.draftwatch.harness.ReportParser;
import dev.draftwatch.harness.ReportRule;
import dev.draftwatch.stats.MetricCalculator;
import dev.draftwatch.testing.FakeExecutor;
import dev.draftwatch.testing.ReportScenario;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

/**
 * Each corrupted report yields FAILED naming the violated rule. The fake harness
 * runs through an executor and the poller; each corruption must leave the job FAILED with
 * {@code INVALID_REPORT}, its cause naming exactly that rule, and must not be retried.
 */
@RunWith(Parameterized.class)
public class JobCorruptionTest {
  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private final ReportRule rule;

  public JobCorruptionTest(String name, ReportRule rule) {
    this.rule = rule;
  }

  @Parameters(name = "{0}")
  public static Collection<Object[]> rules() {
    List<Object[]> cases = new ArrayList<>();
    for (ReportRule rule : ReportRule.values()) {
      cases.add(new Object[] {rule.wireName(), rule});
    }
    return cases;
  }

  @Test
  public void corruptedReportFailsTheJobNamingTheRule() {
    Path dir = tmp.getRoot().toPath();
    ReportScenario s = ReportScenario.greedy(dir);
    MeasurementSpec spec =
        MeasurementSpec.of(
            s.checkpoint(),
            s.probe(),
            s.harnessCommand(Map.of("DRAFTWATCH_FAKE_CORRUPT", rule.wireName())),
            dir,
            dir.resolve("raw/j1"),
            FakeExecutor.NAME);
    FakeExecutor executor = new FakeExecutor(Clock.systemUTC());
    JobPoller poller =
        new JobPoller(executor, new ReportParser(new MetricCalculator()), Clock.systemUTC());
    Instant now = Instant.now();
    Job job = Job.created("j1", spec, now).submitted(executor.submit(spec.jobSpec("j1", 1)), now);

    Job polled = poller.poll(job).job();

    assertEquals(JobState.FAILED, polled.state());
    assertEquals(Optional.of(FailureReason.INVALID_REPORT), polled.failureReason());
    String cause = polled.lastChange().cause();
    assertTrue(cause, cause.startsWith("report rule " + rule.wireName() + ": "));
    assertTrue(!new RetryPolicy(5).allowsRetry(polled));
  }
}
