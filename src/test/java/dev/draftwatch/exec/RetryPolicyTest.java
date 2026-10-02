package dev.draftwatch.exec;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.draftwatch.testing.ReportScenario;
import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class RetryPolicyTest {
  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

  private Job failedOnAttempt(int attempts, FailureReason reason) {
    Path dir = tmp.getRoot().toPath();
    ReportScenario s = ReportScenario.greedy(dir);
    MeasurementSpec spec =
        MeasurementSpec.of(
            s.checkpoint(), s.probe(), s.harnessCommand(Map.of()), dir, dir.resolve("raw/j"),
            "fake");
    RetryPolicy unlimited = new RetryPolicy(100);
    Job job = Job.created("j", spec, T0);
    for (int a = 1; a <= attempts; a++) {
      job =
          job.submitted(JobHandle.of("fake", "n" + a, spec.runDir(a), T0, Optional.empty()), T0)
              .running(T0, "started")
              .failed(a == attempts ? reason : FailureReason.UNEXPECTED_EXIT, "x", T0);
      if (a < attempts) {
        job = job.retried(unlimited, T0);
      }
    }
    return job;
  }

  @Test
  public void onlyTransientReasonsAreRetryable() {
    EnumSet<FailureReason> retryable =
        EnumSet.of(
            FailureReason.NODE_FAILURE,
            FailureReason.PREEMPTED_NO_REQUEUE,
            FailureReason.UNEXPECTED_EXIT);
    for (FailureReason reason : FailureReason.values()) {
      assertEquals(reason.toString(), retryable.contains(reason), RetryPolicy.isRetryable(reason));
    }
  }

  @Test
  public void maxRetriesCountsRetriesAfterTheFirstAttempt() {
    RetryPolicy policy = new RetryPolicy(2);
    assertTrue(policy.allowsRetry(failedOnAttempt(1, FailureReason.UNEXPECTED_EXIT)));
    assertTrue(policy.allowsRetry(failedOnAttempt(2, FailureReason.UNEXPECTED_EXIT)));
    assertFalse(policy.allowsRetry(failedOnAttempt(3, FailureReason.UNEXPECTED_EXIT)));
  }

  @Test
  public void zeroRetriesMeansOneAttempt() {
    assertFalse(new RetryPolicy(0).allowsRetry(failedOnAttempt(1, FailureReason.NODE_FAILURE)));
  }

  @Test
  public void nonRetryableReasonIsNeverRetried() {
    assertFalse(new RetryPolicy(9).allowsRetry(failedOnAttempt(1, FailureReason.INVALID_REPORT)));
  }

  @Test(expected = IllegalArgumentException.class)
  public void negativeMaxRetriesIsRejected() {
    new RetryPolicy(-1);
  }

  @Test
  public void exitCodesMapPerContract() {
    assertEquals(FailureReason.BAD_ARGUMENTS, FailureReason.forExitCode(2));
    assertEquals(FailureReason.MODEL_LOAD, FailureReason.forExitCode(3));
    assertEquals(FailureReason.OUT_OF_MEMORY, FailureReason.forExitCode(4));
    assertEquals(FailureReason.BACKEND_COUNTERS, FailureReason.forExitCode(5));
    for (int code : new int[] {1, 6, 127, 137, 255, -1}) {
      assertEquals(FailureReason.UNEXPECTED_EXIT, FailureReason.forExitCode(code));
    }
  }

  @Test(expected = IllegalArgumentException.class)
  public void exitZeroIsNotAFailure() {
    FailureReason.forExitCode(0);
  }
}
