package dev.draftwatch.exec;

import dev.draftwatch.domain.WireNamed;

/**
 * Why a job attempt FAILED (ARCHITECTURE.md, "Job state machine"). Whether a reason is retried is
 * {@link RetryPolicy}'s decision. {@code BACKEND_COUNTERS} and {@code SUBMISSION_FAILED} complete
 * the architecture's list (DECISIONS.md D30).
 */
public enum FailureReason implements WireNamed {
  /** The node running the job failed. */
  NODE_FAILURE("node_failure"),
  /** The job was preempted and the config does not requeue. */
  PREEMPTED_NO_REQUEUE("preempted_no_requeue"),
  /** Harness exit code other than 0, 2, 3, 4, 5, or the process vanished without one. */
  UNEXPECTED_EXIT("unexpected_exit"),
  /** Harness exit code 2. */
  BAD_ARGUMENTS("bad_arguments"),
  /** Harness exit code 3. */
  MODEL_LOAD("model_load"),
  /** Harness exit code 4, or the scheduler reports out of memory. */
  OUT_OF_MEMORY("out_of_memory"),
  /** Harness exit code 5: the backend cannot report the required counters. */
  BACKEND_COUNTERS("backend_counters"),
  /** The scheduler stopped the job at its time limit. */
  TIMEOUT("timeout"),
  /** Exit 0, but the report broke a contract rule. */
  INVALID_REPORT("invalid_report"),
  /** The executor refused or could not start the job. */
  SUBMISSION_FAILED("submission_failed");

  private final String wireName;

  FailureReason(String wireName) {
    this.wireName = wireName;
  }

  @Override
  public String wireName() {
    return wireName;
  }

  /** The reason for a harness exit code (MEASUREMENT_CONTRACT.md, "Invocation"); 0 is not one. */
  public static FailureReason forExitCode(int code) {
    switch (code) {
      case 0:
        throw new IllegalArgumentException("exit code 0 is not a failure");
      case 2:
        return BAD_ARGUMENTS;
      case 3:
        return MODEL_LOAD;
      case 4:
        return OUT_OF_MEMORY;
      case 5:
        return BACKEND_COUNTERS;
      default:
        return UNEXPECTED_EXIT;
    }
  }
}
