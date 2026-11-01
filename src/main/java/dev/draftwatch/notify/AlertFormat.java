package dev.draftwatch.notify;

import dev.draftwatch.domain.Checkpoint;
import dev.draftwatch.events.DetectionEvent;
import dev.draftwatch.events.DetectionSubject;
import dev.draftwatch.events.MeasurementFailed;
import dev.draftwatch.exec.FailureReason;
import dev.draftwatch.exec.Job;
import dev.draftwatch.exec.StateChange;

/**
 * The one-line alert text shared by every notifier: when, what, where, why, and the result file
 * the numbers come from (or, for a failed measurement, the checkpoint that still has no result).
 */
public final class AlertFormat {
  private AlertFormat() {}

  /**
   * For example {@code 2026-10-01T12:00:00Z REGRESSION target run, step 500, probe chat, job j1:
   * absolute_drop(...): ...; result /state/results/run/....json}.
   */
  public static String line(DetectionEvent event) {
    DetectionSubject s = event.subject();
    return event.at() + " " + event.kind() + " target " + s.target() + ", step " + s.step()
        + ", probe " + s.probeId() + ", job " + s.jobId() + ": " + event.explanation()
        + "; result " + s.resultFile();
  }

  /**
   * For example {@code 2026-10-01T12:00:00Z FAILED target run, step 500, probe chat, job j1,
   * attempt 1: submission_failed: sbatch refused the job; checkpoint /runs/checkpoint-500}.
   */
  public static String line(MeasurementFailed event) {
    Job job = event.job();
    Checkpoint c = job.spec().checkpoint();
    StateChange last = job.lastChange();
    return event.at() + " FAILED target " + c.targetName() + ", step " + c.step() + ", probe "
        + job.spec().probe().probe().id() + ", job " + job.id() + ", attempt " + job.attempt()
        + ": " + last.failureReason().map(FailureReason::wireName).orElse("failed") + ": "
        + last.cause() + "; checkpoint " + c.path();
  }
}
