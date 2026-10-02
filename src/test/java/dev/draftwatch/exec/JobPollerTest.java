package dev.draftwatch.exec;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import dev.draftwatch.harness.ReportParser;
import dev.draftwatch.stats.MetricCalculator;
import dev.draftwatch.testing.ReportScenario;
import dev.draftwatch.testing.ScriptedExecutor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Each executor status, mapped onto the state machine by one poll. */
public class JobPollerTest {
  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant T5 = T0.plusSeconds(5);
  private static final Instant T9 = T0.plusSeconds(9);
  private static final Instant NOW = T0.plusSeconds(60);

  private final ScriptedExecutor executor = new ScriptedExecutor();
  private final JobPoller poller =
      new JobPoller(
          executor, new ReportParser(new MetricCalculator()), Clock.fixed(NOW, ZoneOffset.UTC));
  private ReportScenario scenario;
  private MeasurementSpec spec;

  @Before
  public void setUp() {
    Path dir = tmp.getRoot().toPath();
    scenario = ReportScenario.greedy(dir);
    spec =
        MeasurementSpec.of(
            scenario.checkpoint(),
            scenario.probe(),
            scenario.harnessCommand(Map.of()),
            dir,
            dir.resolve("raw/j1"),
            "scripted");
  }

  private Job submittedJob() {
    Job job = Job.created("j1", spec, T0);
    return job.submitted(executor.submit(spec.jobSpec("j1", 1)), T0);
  }

  /** Puts a valid fake report where attempt 1 of the job expects it. */
  private void writeValidReport() throws IOException {
    assertEquals(0, scenario.run(Map.of()));
    Files.createDirectories(spec.runDir(1));
    Files.copy(scenario.reportPath(), spec.reportPath(1));
  }

  @Test
  public void queuedLeavesASubmittedJobAlone() {
    executor.then(ExecutorStatus.queued());
    Job job = submittedJob();
    assertEquals(job, poller.poll(job).job());
  }

  @Test
  public void runningStartsTheJobAtTheExecutorsStartTime() {
    executor.then(ExecutorStatus.running(Optional.of(T5)));
    Job job = poller.poll(submittedJob()).job();
    assertEquals(JobState.RUNNING, job.state());
    assertEquals(Optional.of(T5), job.runningSince());
  }

  @Test
  public void queuedAfterRunningIsARequeue() {
    executor.then(ExecutorStatus.running(Optional.of(T5))).then(ExecutorStatus.queued());
    Job job = poller.poll(poller.poll(submittedJob()).job()).job();
    assertEquals(JobState.SUBMITTED, job.state());
    assertEquals("requeued by executor", job.lastChange().cause());
  }

  @Test
  public void exitZeroWithValidReportSucceedsWithTheReport() throws IOException {
    writeValidReport();
    executor.then(ExecutorStatus.exited(0, Optional.of(T5), Optional.of(T9)));
    JobPoller.Result result = poller.poll(submittedJob());
    assertEquals(JobState.SUCCEEDED, result.job().state());
    assertEquals(T9, result.job().lastChange().at());
    assertEquals(Optional.of(T5), result.job().runningSince());
    assertTrue(result.report().isPresent());
    assertEquals(12.0 / 26.0, result.report().get().seeds().get(0).alpha(), 0.0);
  }

  @Test
  public void exitZeroWithoutReportFailsAsInvalidReport() {
    executor.then(ExecutorStatus.exited(0, Optional.of(T5), Optional.of(T9)));
    JobPoller.Result result = poller.poll(submittedJob());
    assertEquals(Optional.of(FailureReason.INVALID_REPORT), result.job().failureReason());
    assertTrue(result.job().lastChange().cause().startsWith("report rule report_missing:"));
    assertEquals(Optional.empty(), result.report());
  }

  @Test
  public void contractExitCodesMapToTheirReasons() {
    int[] codes = {2, 3, 4, 5, 1, 139};
    FailureReason[] reasons = {
      FailureReason.BAD_ARGUMENTS,
      FailureReason.MODEL_LOAD,
      FailureReason.OUT_OF_MEMORY,
      FailureReason.BACKEND_COUNTERS,
      FailureReason.UNEXPECTED_EXIT,
      FailureReason.UNEXPECTED_EXIT
    };
    for (int i = 0; i < codes.length; i++) {
      ScriptedExecutor ex =
          new ScriptedExecutor()
              .then(ExecutorStatus.exited(codes[i], Optional.of(T5), Optional.of(T9)));
      JobPoller p =
          new JobPoller(ex, new ReportParser(new MetricCalculator()), Clock.systemUTC());
      Job job =
          Job.created("j1", spec, T0).submitted(ex.submit(spec.jobSpec("j1", 1)), T0);
      Job polled = p.poll(job).job();
      assertEquals("exit " + codes[i], Optional.of(reasons[i]), polled.failureReason());
      assertTrue(polled.lastChange().cause().contains("exited with code " + codes[i]));
    }
  }

  @Test
  public void lostAttemptFailsAsUnexpectedExit() {
    executor.then(ExecutorStatus.lost("process 42 ended without writing exit_code"));
    Job job = poller.poll(submittedJob()).job();
    assertEquals(Optional.of(FailureReason.UNEXPECTED_EXIT), job.failureReason());
    assertEquals("process 42 ended without writing exit_code", job.lastChange().cause());
    assertEquals(NOW, job.lastChange().at());
  }

  @Test
  public void jobsThatAreNotSubmittedOrRunningAreReturnedUnchanged() {
    Job created = Job.created("j1", spec, T0);
    assertEquals(created, poller.poll(created).job());
    assertTrue(executor.submitted().isEmpty());
  }
}
