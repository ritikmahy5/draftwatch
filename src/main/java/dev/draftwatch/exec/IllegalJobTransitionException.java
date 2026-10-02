package dev.draftwatch.exec;

/** A job was asked to make a transition the state machine does not allow. */
public final class IllegalJobTransitionException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final JobState from;
  private final JobState to;

  public IllegalJobTransitionException(JobState from, JobState to, String why) {
    super("illegal job transition " + from + " -> " + to + ": " + why);
    this.from = from;
    this.to = to;
  }

  public JobState from() {
    return from;
  }

  public JobState to() {
    return to;
  }
}
