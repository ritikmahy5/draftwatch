package dev.draftwatch.exec;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import dev.draftwatch.testing.ReportScenario;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class JobTest {
  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
  private MeasurementSpec spec;

  @Before
  public void setUp() {
    Path dir = tmp.getRoot().toPath();
    ReportScenario s = ReportScenario.greedy(dir);
    spec =
        MeasurementSpec.of(
            s.checkpoint(), s.probe(), s.harnessCommand(Map.of()), dir, dir.resolve("raw/j1"),
            "fake");
  }

  private JobHandle handle(int n) {
    return JobHandle.of("fake", "n" + n, spec.runDir(n), T0, Optional.empty());
  }

  private Job submitted() {
    return Job.created("j1", spec, T0).submitted(handle(1), T0.plusSeconds(1));
  }

  @Test
  public void happyPathRecordsEveryChange() {
    Job job = submitted().running(T0.plusSeconds(2), "started").succeeded(T0.plusSeconds(9));
    assertEquals(JobState.SUCCEEDED, job.state());
    assertEquals(4, job.history().size());
    assertEquals(Optional.of(T0.plusSeconds(2)), job.runningSince());
    assertEquals(Optional.of(handle(1)), job.handle());
    assertEquals(Optional.empty(), job.failureReason());
  }

  @Test
  public void failedJobCarriesItsReason() {
    Job job =
        submitted().running(T0, "started").failed(FailureReason.MODEL_LOAD, "exit 3", T0);
    assertEquals(Optional.of(FailureReason.MODEL_LOAD), job.failureReason());
    assertEquals("exit 3", job.lastChange().cause());
  }

  @Test
  public void retryStartsTheNextAttemptWithoutAHandle() {
    RetryPolicy policy = new RetryPolicy(2);
    Job job =
        submitted()
            .running(T0, "started")
            .failed(FailureReason.UNEXPECTED_EXIT, "exit 1", T0)
            .retried(policy, T0.plusSeconds(5));
    assertEquals(JobState.CREATED, job.state());
    assertEquals(2, job.attempt());
    assertEquals(Optional.empty(), job.handle());
    assertEquals(Optional.empty(), job.runningSince());
  }

  @Test
  public void retryRefusedByPolicyThrows() {
    Job failed =
        submitted().running(T0, "started").failed(FailureReason.OUT_OF_MEMORY, "exit 4", T0);
    try {
      failed.retried(new RetryPolicy(5), T0);
      fail("expected IllegalJobTransitionException");
    } catch (IllegalJobTransitionException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("out_of_memory is not retried"));
    }
  }

  @Test(expected = IllegalJobTransitionException.class)
  public void illegalTransitionOnAJobThrows() {
    Job.created("j1", spec, T0).succeeded(T0);
  }

  @Test(expected = IllegalJobTransitionException.class)
  public void terminalJobsCannotMove() {
    submitted().running(T0, "started").succeeded(T0).cancelled(T0, "too late");
  }

  @Test
  public void restoreReplaysAValidHistory() {
    Job job =
        submitted()
            .running(T0, "started")
            .failed(FailureReason.UNEXPECTED_EXIT, "exit 1", T0)
            .retried(new RetryPolicy(1), T0)
            .submitted(handle(2), T0);
    assertEquals(job, Job.restore("j1", spec, job.handle(), job.history()));
  }

  @Test
  public void restoreRejectsAnIllegalHistory() {
    List<StateChange> history = new ArrayList<>(submitted().history());
    history.add(
        StateChange.of(
            Optional.of(JobState.SUBMITTED), JobState.SUCCEEDED, T0, 1, "?", Optional.empty()));
    try {
      Job.restore("j1", spec, Optional.of(handle(1)), history);
      fail("expected IllegalJobTransitionException");
    } catch (IllegalJobTransitionException e) {
      assertEquals(JobState.SUBMITTED, e.from());
    }
  }

  @Test
  public void restoreRejectsInconsistentLinksAndAttempts() {
    List<StateChange> broken = new ArrayList<>(submitted().history());
    broken.add(
        StateChange.of(
            Optional.of(JobState.CREATED), JobState.SUBMITTED, T0, 1, "?", Optional.empty()));
    assertRestoreRejected(broken, "previous entry ended in SUBMITTED");

    List<StateChange> jump = new ArrayList<>(submitted().history());
    jump.add(
        StateChange.of(
            Optional.of(JobState.SUBMITTED), JobState.RUNNING, T0, 2, "?", Optional.empty()));
    assertRestoreRejected(jump, "has attempt 2, expected 1");
  }

  private void assertRestoreRejected(List<StateChange> history, String fragment) {
    try {
      Job.restore("j1", spec, Optional.of(handle(1)), history);
      fail("expected IllegalArgumentException");
    } catch (IllegalArgumentException e) {
      assertTrue(e.getMessage(), e.getMessage().contains(fragment));
    }
  }
}
