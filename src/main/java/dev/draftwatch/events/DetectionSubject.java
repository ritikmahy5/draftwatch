package dev.draftwatch.events;

import dev.draftwatch.domain.Measurement;
import java.nio.file.Path;
import java.util.Objects;

/** The measurement a detection is about, with the result file its numbers come from. */
public final class DetectionSubject {
  private final String target;
  private final String probeId;
  private final String probeHash;
  private final long step;
  private final String fingerprint;
  private final String jobId;
  private final Path resultFile;

  private DetectionSubject(
      String target,
      String probeId,
      String probeHash,
      long step,
      String fingerprint,
      String jobId,
      Path resultFile) {
    this.target = target;
    this.probeId = probeId;
    this.probeHash = probeHash;
    this.step = step;
    this.fingerprint = fingerprint;
    this.jobId = jobId;
    this.resultFile = resultFile;
  }

  public static DetectionSubject of(Measurement m, Path resultFile) {
    return new DetectionSubject(
        m.provenance().targetName(),
        m.provenance().probeId(),
        m.probeHash(),
        m.provenance().checkpoint().step(),
        m.fingerprint(),
        m.jobId(),
        Objects.requireNonNull(resultFile, "resultFile"));
  }

  public static DetectionSubject of(
      String target,
      String probeId,
      String probeHash,
      long step,
      String fingerprint,
      String jobId,
      Path resultFile) {
    return new DetectionSubject(
        Objects.requireNonNull(target, "target"),
        Objects.requireNonNull(probeId, "probeId"),
        Objects.requireNonNull(probeHash, "probeHash"),
        step,
        Objects.requireNonNull(fingerprint, "fingerprint"),
        Objects.requireNonNull(jobId, "jobId"),
        Objects.requireNonNull(resultFile, "resultFile"));
  }

  public String target() {
    return target;
  }

  public String probeId() {
    return probeId;
  }

  public String probeHash() {
    return probeHash;
  }

  public long step() {
    return step;
  }

  public String fingerprint() {
    return fingerprint;
  }

  public String jobId() {
    return jobId;
  }

  public Path resultFile() {
    return resultFile;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof DetectionSubject)) {
      return false;
    }
    DetectionSubject that = (DetectionSubject) o;
    return target.equals(that.target)
        && probeId.equals(that.probeId)
        && probeHash.equals(that.probeHash)
        && step == that.step
        && fingerprint.equals(that.fingerprint)
        && jobId.equals(that.jobId)
        && resultFile.equals(that.resultFile);
  }

  @Override
  public int hashCode() {
    return Objects.hash(target, probeId, probeHash, step, fingerprint, jobId, resultFile);
  }

  @Override
  public String toString() {
    return "target " + target + " step " + step + " probe " + probeId + " job " + jobId;
  }
}
