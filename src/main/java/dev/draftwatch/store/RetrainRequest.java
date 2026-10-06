package dev.draftwatch.store;

import dev.draftwatch.exec.JobHandle;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/**
 * A retrain job draftwatch submitted, and the regression that caused it (DECISIONS.md D76, D77).
 * There is at most one per target, draft id, and draft fingerprint.
 */
public final class RetrainRequest {
  private final String retrainId;
  private final String target;
  private final String probeId;
  private final String draftId;
  private final String draftFingerprint;
  private final String triggeredByJob;
  private final long checkpointStep;
  private final Path resultFile;
  private final JobHandle handle;

  private RetrainRequest(
      String retrainId,
      String target,
      String probeId,
      String draftId,
      String draftFingerprint,
      String triggeredByJob,
      long checkpointStep,
      Path resultFile,
      JobHandle handle) {
    this.retrainId = retrainId;
    this.target = target;
    this.probeId = probeId;
    this.draftId = draftId;
    this.draftFingerprint = draftFingerprint;
    this.triggeredByJob = triggeredByJob;
    this.checkpointStep = checkpointStep;
    this.resultFile = resultFile;
    this.handle = handle;
  }

  /**
   * @param triggeredByJob the measurement job whose result regressed
   * @param resultFile that result's file
   */
  public static RetrainRequest of(
      String retrainId,
      String target,
      String probeId,
      String draftId,
      String draftFingerprint,
      String triggeredByJob,
      long checkpointStep,
      Path resultFile,
      JobHandle handle) {
    return new RetrainRequest(
        Objects.requireNonNull(retrainId, "retrainId"),
        Objects.requireNonNull(target, "target"),
        Objects.requireNonNull(probeId, "probeId"),
        Objects.requireNonNull(draftId, "draftId"),
        Objects.requireNonNull(draftFingerprint, "draftFingerprint"),
        Objects.requireNonNull(triggeredByJob, "triggeredByJob"),
        checkpointStep,
        Objects.requireNonNull(resultFile, "resultFile"),
        Objects.requireNonNull(handle, "handle"));
  }

  public String retrainId() {
    return retrainId;
  }

  public String target() {
    return target;
  }

  public String probeId() {
    return probeId;
  }

  public String draftId() {
    return draftId;
  }

  public String draftFingerprint() {
    return draftFingerprint;
  }

  public String triggeredByJob() {
    return triggeredByJob;
  }

  public long checkpointStep() {
    return checkpointStep;
  }

  public Path resultFile() {
    return resultFile;
  }

  /** The executor's handle: where the job runs and where its output goes. */
  public JobHandle handle() {
    return handle;
  }

  public Instant submittedAt() {
    return handle.submittedAt();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof RetrainRequest)) {
      return false;
    }
    RetrainRequest that = (RetrainRequest) o;
    return retrainId.equals(that.retrainId)
        && target.equals(that.target)
        && probeId.equals(that.probeId)
        && draftId.equals(that.draftId)
        && draftFingerprint.equals(that.draftFingerprint)
        && triggeredByJob.equals(that.triggeredByJob)
        && checkpointStep == that.checkpointStep
        && resultFile.equals(that.resultFile)
        && handle.equals(that.handle);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        retrainId, target, probeId, draftId, draftFingerprint, triggeredByJob, checkpointStep,
        resultFile, handle);
  }

  @Override
  public String toString() {
    return "retrain " + retrainId + " of draft " + draftId + " (" + draftFingerprint
        + ") for target " + target + ", after job " + triggeredByJob;
  }
}
