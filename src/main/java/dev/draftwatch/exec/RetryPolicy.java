package dev.draftwatch.exec;

import java.util.EnumSet;
import java.util.Set;

/**
 * Decides whether a FAILED job may go back to CREATED. Only failures that a second attempt could
 * survive are retried; a job is attempted at most {@code 1 + max_retries} times.
 */
public final class RetryPolicy {
  private static final Set<FailureReason> RETRYABLE =
      EnumSet.of(
          FailureReason.NODE_FAILURE,
          FailureReason.PREEMPTED_NO_REQUEUE,
          FailureReason.UNEXPECTED_EXIT);

  private final int maxRetries;

  /** @param maxRetries retries allowed after the first attempt; at least 0 */
  public RetryPolicy(int maxRetries) {
    if (maxRetries < 0) {
      throw new IllegalArgumentException("max_retries must be >= 0, was " + maxRetries);
    }
    this.maxRetries = maxRetries;
  }

  public int maxRetries() {
    return maxRetries;
  }

  /** True for the reasons a retry could fix; the others fail the same way every time. */
  public static boolean isRetryable(FailureReason reason) {
    return RETRYABLE.contains(reason);
  }

  /** True if {@code job} is FAILED for a retryable reason and has retries left. */
  public boolean allowsRetry(Job job) {
    return job.state() == JobState.FAILED
        && job.failureReason().map(RetryPolicy::isRetryable).orElse(false)
        && job.attempt() - 1 < maxRetries;
  }

  /** Why {@link #allowsRetry} is false, for error messages. */
  String refusal(Job job) {
    if (job.state() != JobState.FAILED) {
      return "job is " + job.state() + ", not FAILED";
    }
    if (!job.failureReason().map(RetryPolicy::isRetryable).orElse(false)) {
      return job.failureReason().map(FailureReason::wireName).orElse("unknown") + " is not retried";
    }
    return "attempt " + job.attempt() + " used the last of " + maxRetries + " retries";
  }
}
