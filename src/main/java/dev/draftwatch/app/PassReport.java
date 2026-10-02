package dev.draftwatch.app;

import dev.draftwatch.discovery.Discovery;
import dev.draftwatch.exec.Job;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** What one {@code watch} pass did, for printing. */
public final class PassReport {
  private final Instant at;
  private final List<String> errors;
  private final List<Discovery.Skipped> skipped;
  private final List<Job> baselineSubmitted;
  private final List<Submitted> submitted;
  private final Map<String, Integer> notMeasuredByRule;
  private final List<Job> finished;
  private final int stillActive;

  /** A job submitted because the trigger chain accepted its checkpoint. */
  public static final class Submitted {
    private final Job job;
    private final String why;

    public Submitted(Job job, String why) {
      this.job = Objects.requireNonNull(job, "job");
      this.why = Objects.requireNonNull(why, "why");
    }

    public Job job() {
      return job;
    }

    /** The chain's reason, for example {@code always_final: final checkpoint}. */
    public String why() {
      return why;
    }
  }

  PassReport(
      Instant at,
      List<String> errors,
      List<Discovery.Skipped> skipped,
      List<Job> baselineSubmitted,
      List<Submitted> submitted,
      Map<String, Integer> notMeasuredByRule,
      List<Job> finished,
      int stillActive) {
    this.at = Objects.requireNonNull(at, "at");
    this.errors = List.copyOf(errors);
    this.skipped = List.copyOf(skipped);
    this.baselineSubmitted = List.copyOf(baselineSubmitted);
    this.submitted = List.copyOf(submitted);
    this.notMeasuredByRule = Map.copyOf(notMeasuredByRule);
    this.finished = List.copyOf(finished);
    this.stillActive = stillActive;
  }

  public Instant at() {
    return at;
  }

  /** Problems that need attention: unresolvable probes, unusable baselines. */
  public List<String> errors() {
    return errors;
  }

  public List<Discovery.Skipped> skipped() {
    return skipped;
  }

  /** Measurements of baseline checkpoints that had no result (SPEC.md F5). */
  public List<Job> baselineSubmitted() {
    return baselineSubmitted;
  }

  public List<Submitted> submitted() {
    return submitted;
  }

  /** Checkpoint-probe pairs the trigger chain rejected, counted by deciding rule. */
  public Map<String, Integer> notMeasuredByRule() {
    return notMeasuredByRule;
  }

  /** Jobs that finished during this pass (succeeded, failed for good, or were cancelled). */
  public List<Job> finished() {
    return finished;
  }

  /** Jobs still queued or running after this pass. */
  public int stillActive() {
    return stillActive;
  }
}
