package dev.draftwatch.exec;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * What an executor returns for a submitted attempt and needs to find it again, possibly from a
 * later draftwatch process: the executor's own id for it (a PID, a Slurm job id) and the
 * attempt's run directory.
 */
public final class JobHandle {
  private final String executor;
  private final String nativeId;
  private final Path runDir;
  private final Instant submittedAt;
  private final Optional<Instant> nativeStartTime;

  private JobHandle(
      String executor,
      String nativeId,
      Path runDir,
      Instant submittedAt,
      Optional<Instant> nativeStartTime) {
    this.executor = executor;
    this.nativeId = nativeId;
    this.runDir = runDir;
    this.submittedAt = submittedAt;
    this.nativeStartTime = nativeStartTime;
  }

  /**
   * Creates a handle.
   *
   * @param nativeStartTime when the executor reports one, the start time of the native process,
   *     used to tell it apart from an unrelated process that later reuses its id
   */
  public static JobHandle of(
      String executor,
      String nativeId,
      Path runDir,
      Instant submittedAt,
      Optional<Instant> nativeStartTime) {
    Objects.requireNonNull(executor, "executor");
    Objects.requireNonNull(nativeId, "nativeId");
    if (nativeId.isEmpty()) {
      throw new IllegalArgumentException("nativeId must not be empty");
    }
    return new JobHandle(
        executor,
        nativeId,
        Objects.requireNonNull(runDir, "runDir"),
        Objects.requireNonNull(submittedAt, "submittedAt"),
        Objects.requireNonNull(nativeStartTime, "nativeStartTime"));
  }

  /** The executor type name, for example {@code local}. */
  public String executor() {
    return executor;
  }

  public String nativeId() {
    return nativeId;
  }

  public Path runDir() {
    return runDir;
  }

  public Instant submittedAt() {
    return submittedAt;
  }

  public Optional<Instant> nativeStartTime() {
    return nativeStartTime;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof JobHandle)) {
      return false;
    }
    JobHandle that = (JobHandle) o;
    return executor.equals(that.executor)
        && nativeId.equals(that.nativeId)
        && runDir.equals(that.runDir)
        && submittedAt.equals(that.submittedAt)
        && nativeStartTime.equals(that.nativeStartTime);
  }

  @Override
  public int hashCode() {
    return Objects.hash(executor, nativeId, runDir, submittedAt, nativeStartTime);
  }

  @Override
  public String toString() {
    return executor + ":" + nativeId;
  }
}
