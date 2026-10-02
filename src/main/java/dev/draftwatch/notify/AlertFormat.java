package dev.draftwatch.notify;

import dev.draftwatch.events.DetectionEvent;
import dev.draftwatch.events.DetectionSubject;

/**
 * The one-line alert text shared by every notifier: when, what, where, why, and the result file
 * the numbers come from.
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
}
