package dev.draftwatch.domain;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Where a measurement came from: every field SPEC.md F4 requires a result to record, so each
 * reported number can be traced to its checkpoint, probe, job, and raw report file.
 *
 * <p>Built with a {@link Builder} because it has seventeen fields, all required.
 */
public final class Provenance {
  private final Checkpoint checkpoint;
  private final String probeId;
  private final String probeHash;
  private final String draftId;
  private final String draftFingerprint;
  private final String harnessVersion;
  private final String backend;
  private final String dtype;
  private final Estimator estimator;
  private final List<Integer> seeds;
  private final String promptSetSha256;
  private final String executor;
  private final String jobId;
  private final int attempt;
  private final Instant startTime;
  private final Instant endTime;
  private final Path rawReportPath;

  private Provenance(Builder b, List<Integer> seeds) {
    this.checkpoint = b.checkpoint;
    this.probeId = b.probeId;
    this.probeHash = b.probeHash;
    this.draftId = b.draftId;
    this.draftFingerprint = b.draftFingerprint;
    this.harnessVersion = b.harnessVersion;
    this.backend = b.backend;
    this.dtype = b.dtype;
    this.estimator = b.estimator;
    this.seeds = seeds;
    this.promptSetSha256 = b.promptSetSha256;
    this.executor = b.executor;
    this.jobId = b.jobId;
    this.attempt = b.attempt;
    this.startTime = b.startTime;
    this.endTime = b.endTime;
    this.rawReportPath = b.rawReportPath;
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Target name, checkpoint path, step, fingerprint, type, and base model if an adapter. */
  public Checkpoint checkpoint() {
    return checkpoint;
  }

  public String targetName() {
    return checkpoint.targetName();
  }

  public String probeId() {
    return probeId;
  }

  public String probeHash() {
    return probeHash;
  }

  public String draftId() {
    return draftId;
  }

  public String draftFingerprint() {
    return draftFingerprint;
  }

  public String harnessVersion() {
    return harnessVersion;
  }

  public String backend() {
    return backend;
  }

  public String dtype() {
    return dtype;
  }

  public Estimator estimator() {
    return estimator;
  }

  public List<Integer> seeds() {
    return seeds;
  }

  public String promptSetSha256() {
    return promptSetSha256;
  }

  /** Executor type name from the config, for example {@code local} or {@code slurm}. */
  public String executor() {
    return executor;
  }

  public String jobId() {
    return jobId;
  }

  /** 1 for the first attempt; incremented by each retry. */
  public int attempt() {
    return attempt;
  }

  public Instant startTime() {
    return startTime;
  }

  public Instant endTime() {
    return endTime;
  }

  /** The harness's report, exactly as written, under the state directory. */
  public Path rawReportPath() {
    return rawReportPath;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Provenance)) {
      return false;
    }
    Provenance that = (Provenance) o;
    return checkpoint.equals(that.checkpoint)
        && probeId.equals(that.probeId)
        && probeHash.equals(that.probeHash)
        && draftId.equals(that.draftId)
        && draftFingerprint.equals(that.draftFingerprint)
        && harnessVersion.equals(that.harnessVersion)
        && backend.equals(that.backend)
        && dtype.equals(that.dtype)
        && estimator == that.estimator
        && seeds.equals(that.seeds)
        && promptSetSha256.equals(that.promptSetSha256)
        && executor.equals(that.executor)
        && jobId.equals(that.jobId)
        && attempt == that.attempt
        && startTime.equals(that.startTime)
        && endTime.equals(that.endTime)
        && rawReportPath.equals(that.rawReportPath);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        checkpoint,
        probeId,
        probeHash,
        draftId,
        draftFingerprint,
        harnessVersion,
        backend,
        dtype,
        estimator,
        seeds,
        promptSetSha256,
        executor,
        jobId,
        attempt,
        startTime,
        endTime,
        rawReportPath);
  }

  @Override
  public String toString() {
    return "Provenance{job " + jobId + " attempt " + attempt + ", " + checkpoint + "}";
  }

  /** Builder for {@link Provenance}; {@link #build()} rejects missing or invalid fields. */
  public static final class Builder {
    private Checkpoint checkpoint;
    private String probeId;
    private String probeHash;
    private String draftId;
    private String draftFingerprint;
    private String harnessVersion;
    private String backend;
    private String dtype;
    private Estimator estimator;
    private List<Integer> seeds;
    private String promptSetSha256;
    private String executor;
    private String jobId;
    private int attempt;
    private Instant startTime;
    private Instant endTime;
    private Path rawReportPath;

    private Builder() {}

    public Builder checkpoint(Checkpoint checkpoint) {
      this.checkpoint = checkpoint;
      return this;
    }

    public Builder probeId(String probeId) {
      this.probeId = probeId;
      return this;
    }

    public Builder probeHash(String probeHash) {
      this.probeHash = probeHash;
      return this;
    }

    public Builder draftId(String draftId) {
      this.draftId = draftId;
      return this;
    }

    public Builder draftFingerprint(String draftFingerprint) {
      this.draftFingerprint = draftFingerprint;
      return this;
    }

    public Builder harnessVersion(String harnessVersion) {
      this.harnessVersion = harnessVersion;
      return this;
    }

    public Builder backend(String backend) {
      this.backend = backend;
      return this;
    }

    public Builder dtype(String dtype) {
      this.dtype = dtype;
      return this;
    }

    public Builder estimator(Estimator estimator) {
      this.estimator = estimator;
      return this;
    }

    public Builder seeds(List<Integer> seeds) {
      this.seeds = seeds;
      return this;
    }

    public Builder promptSetSha256(String promptSetSha256) {
      this.promptSetSha256 = promptSetSha256;
      return this;
    }

    public Builder executor(String executor) {
      this.executor = executor;
      return this;
    }

    public Builder jobId(String jobId) {
      this.jobId = jobId;
      return this;
    }

    public Builder attempt(int attempt) {
      this.attempt = attempt;
      return this;
    }

    public Builder startTime(Instant startTime) {
      this.startTime = startTime;
      return this;
    }

    public Builder endTime(Instant endTime) {
      this.endTime = endTime;
      return this;
    }

    public Builder rawReportPath(Path rawReportPath) {
      this.rawReportPath = rawReportPath;
      return this;
    }

    /**
     * Builds the provenance record.
     *
     * @throws NullPointerException naming the first missing field
     * @throws IllegalArgumentException if a field is invalid or {@code endTime} precedes
     *     {@code startTime}
     */
    public Provenance build() {
      Require.nonNull(checkpoint, "checkpoint");
      Names.require(probeId, "probe id");
      Require.sha256Hex(probeHash, "probe_hash");
      Names.require(draftId, "draft id");
      Require.nonBlank(draftFingerprint, "draft_fingerprint");
      Require.nonBlank(harnessVersion, "harness_version");
      Require.nonBlank(backend, "backend");
      Require.nonBlank(dtype, "dtype");
      Require.nonNull(estimator, "estimator");
      List<Integer> seedsCopy = Require.nonEmptyCopy(seeds, "seeds");
      Require.sha256Hex(promptSetSha256, "prompt_set_sha256");
      Require.nonBlank(executor, "executor");
      Require.nonBlank(jobId, "job_id");
      Require.positive(attempt, "attempt");
      Require.nonNull(startTime, "start_time");
      Require.nonNull(endTime, "end_time");
      if (endTime.isBefore(startTime)) {
        throw new IllegalArgumentException(
            "end_time " + endTime + " precedes start_time " + startTime);
      }
      Require.nonNull(rawReportPath, "raw_report_path");
      return new Provenance(this, seedsCopy);
    }
  }
}
