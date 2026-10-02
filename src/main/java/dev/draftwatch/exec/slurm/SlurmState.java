package dev.draftwatch.exec.slurm;

import java.util.Optional;

/**
 * Slurm's job state names (squeue.html and sacct.html, "JOB STATE CODES"), each with the group
 * that decides its engine mapping (ARCHITECTURE.md, "Slurm state mapping"; DECISIONS.md D59).
 * A name not listed here is unknown and handled by D60.
 */
public enum SlurmState {
  PENDING(Group.WAITING),
  CONFIGURING(Group.WAITING),
  REQUEUE_HOLD(Group.WAITING),
  REQUEUE_FED(Group.WAITING),
  SPECIAL_EXIT(Group.WAITING),
  RESV_DEL_HOLD(Group.WAITING),
  RUNNING(Group.ACTIVE),
  COMPLETING(Group.ACTIVE),
  SUSPENDED(Group.ACTIVE),
  STOPPED(Group.ACTIVE),
  SIGNALING(Group.ACTIVE),
  RESIZING(Group.ACTIVE),
  STAGE_OUT(Group.ACTIVE),
  REQUEUED(Group.REQUEUING),
  COMPLETED(Group.TERMINAL),
  FAILED(Group.TERMINAL),
  OUT_OF_MEMORY(Group.TERMINAL),
  TIMEOUT(Group.TERMINAL),
  DEADLINE(Group.TERMINAL),
  NODE_FAIL(Group.TERMINAL),
  BOOT_FAIL(Group.TERMINAL),
  PREEMPTED(Group.TERMINAL),
  CANCELLED(Group.TERMINAL),
  /** Documented, but its meaning for a job here is unclear, so it is treated as unknown. */
  REVOKED(Group.UNMAPPED);

  /** How a state is mapped. */
  public enum Group {
    /** Queued or held: the engine job is SUBMITTED. */
    WAITING,
    /** Has, or is releasing, an allocation: the engine job is RUNNING. */
    ACTIVE,
    /** Being requeued: SUBMITTED if {@code requeue_on_preempt}, else FAILED. */
    REQUEUING,
    /** Finished; the outcome is read from sacct. */
    TERMINAL,
    /** Not mapped; unresolved (D60). */
    UNMAPPED
  }

  private final Group group;

  SlurmState(Group group) {
    this.group = group;
  }

  public Group group() {
    return group;
  }

  /**
   * The state named by squeue's {@code %T} or sacct's {@code State}. sacct appends details, as in
   * {@code CANCELLED by 1001}, and marks truncated text with {@code +}; only the first word
   * counts.
   *
   * @return empty for a name this enum does not list
   */
  public static Optional<SlurmState> parse(String text) {
    String word = text.trim().split("[\\s+]", 2)[0];
    for (SlurmState s : values()) {
      if (s.name().equals(word)) {
        return Optional.of(s);
      }
    }
    return Optional.empty();
  }
}
