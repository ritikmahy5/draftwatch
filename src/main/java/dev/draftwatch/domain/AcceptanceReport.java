package dev.draftwatch.domain;

import java.util.List;
import java.util.Objects;

/**
 * A harness report (MEASUREMENT_CONTRACT.md, "Report schema"), held exactly as written.
 *
 * <p>Only reports that passed every contract rule in the report parser are turned into this
 * class, and nothing here repairs or recomputes a value. This class checks only that fields
 * are present.
 *
 * <p>Built with a {@link Builder} because it has fourteen fields.
 */
public final class AcceptanceReport {
  private final int schemaVersion;
  private final String harnessVersion;
  private final String backend;
  private final AdapterHandling adapterHandling;
  private final DraftStructure draftStructure;
  private final Estimator estimator;
  private final String draftId;
  private final String promptSetSha256;
  private final int numPrompts;
  private final Decoding decoding;
  private final List<SeedReport> seeds;
  private final AggregateMetrics aggregate;
  private final Hardware hardware;
  private final double wallClockSeconds;

  private AcceptanceReport(Builder b, List<SeedReport> seeds) {
    this.schemaVersion = b.schemaVersion;
    this.harnessVersion = b.harnessVersion;
    this.backend = b.backend;
    this.adapterHandling = b.adapterHandling;
    this.draftStructure = b.draftStructure;
    this.estimator = b.estimator;
    this.draftId = b.draftId;
    this.promptSetSha256 = b.promptSetSha256;
    this.numPrompts = b.numPrompts;
    this.decoding = b.decoding;
    this.seeds = seeds;
    this.aggregate = b.aggregate;
    this.hardware = b.hardware;
    this.wallClockSeconds = b.wallClockSeconds;
  }

  public static Builder builder() {
    return new Builder();
  }

  public int schemaVersion() {
    return schemaVersion;
  }

  public String harnessVersion() {
    return harnessVersion;
  }

  /** For example {@code vllm==<installed version>}. */
  public String backend() {
    return backend;
  }

  public AdapterHandling adapterHandling() {
    return adapterHandling;
  }

  public DraftStructure draftStructure() {
    return draftStructure;
  }

  public Estimator estimator() {
    return estimator;
  }

  public String draftId() {
    return draftId;
  }

  public String promptSetSha256() {
    return promptSetSha256;
  }

  public int numPrompts() {
    return numPrompts;
  }

  public Decoding decoding() {
    return decoding;
  }

  /** One entry per seed, in report order. */
  public List<SeedReport> seeds() {
    return seeds;
  }

  public AggregateMetrics aggregate() {
    return aggregate;
  }

  public Hardware hardware() {
    return hardware;
  }

  public double wallClockSeconds() {
    return wallClockSeconds;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof AcceptanceReport)) {
      return false;
    }
    AcceptanceReport that = (AcceptanceReport) o;
    return schemaVersion == that.schemaVersion
        && harnessVersion.equals(that.harnessVersion)
        && backend.equals(that.backend)
        && adapterHandling == that.adapterHandling
        && draftStructure == that.draftStructure
        && estimator == that.estimator
        && draftId.equals(that.draftId)
        && promptSetSha256.equals(that.promptSetSha256)
        && numPrompts == that.numPrompts
        && decoding.equals(that.decoding)
        && seeds.equals(that.seeds)
        && aggregate.equals(that.aggregate)
        && hardware.equals(that.hardware)
        && Double.compare(wallClockSeconds, that.wallClockSeconds) == 0;
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        schemaVersion,
        harnessVersion,
        backend,
        adapterHandling,
        draftStructure,
        estimator,
        draftId,
        promptSetSha256,
        numPrompts,
        decoding,
        seeds,
        aggregate,
        hardware,
        wallClockSeconds);
  }

  @Override
  public String toString() {
    return "AcceptanceReport{" + draftId + ", " + estimator.wireName() + ", " + backend + "}";
  }

  /** Builder for {@link AcceptanceReport}; {@link #build()} rejects missing fields. */
  public static final class Builder {
    private int schemaVersion;
    private String harnessVersion;
    private String backend;
    private AdapterHandling adapterHandling;
    private DraftStructure draftStructure;
    private Estimator estimator;
    private String draftId;
    private String promptSetSha256;
    private int numPrompts;
    private Decoding decoding;
    private List<SeedReport> seeds;
    private AggregateMetrics aggregate;
    private Hardware hardware;
    private double wallClockSeconds;

    private Builder() {}

    public Builder schemaVersion(int schemaVersion) {
      this.schemaVersion = schemaVersion;
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

    public Builder adapterHandling(AdapterHandling adapterHandling) {
      this.adapterHandling = adapterHandling;
      return this;
    }

    public Builder draftStructure(DraftStructure draftStructure) {
      this.draftStructure = draftStructure;
      return this;
    }

    public Builder estimator(Estimator estimator) {
      this.estimator = estimator;
      return this;
    }

    public Builder draftId(String draftId) {
      this.draftId = draftId;
      return this;
    }

    public Builder promptSetSha256(String promptSetSha256) {
      this.promptSetSha256 = promptSetSha256;
      return this;
    }

    public Builder numPrompts(int numPrompts) {
      this.numPrompts = numPrompts;
      return this;
    }

    public Builder decoding(Decoding decoding) {
      this.decoding = decoding;
      return this;
    }

    public Builder seeds(List<SeedReport> seeds) {
      this.seeds = seeds;
      return this;
    }

    public Builder aggregate(AggregateMetrics aggregate) {
      this.aggregate = aggregate;
      return this;
    }

    public Builder hardware(Hardware hardware) {
      this.hardware = hardware;
      return this;
    }

    public Builder wallClockSeconds(double wallClockSeconds) {
      this.wallClockSeconds = wallClockSeconds;
      return this;
    }

    /**
     * Builds the report.
     *
     * @throws NullPointerException naming the first missing field
     * @throws IllegalArgumentException if {@code seeds} is empty
     */
    public AcceptanceReport build() {
      Require.nonNull(harnessVersion, "harness_version");
      Require.nonNull(backend, "backend");
      Require.nonNull(adapterHandling, "adapter_handling");
      Require.nonNull(draftStructure, "draft_structure");
      Require.nonNull(estimator, "estimator");
      Require.nonNull(draftId, "draft_id");
      Require.nonNull(promptSetSha256, "prompt_set_sha256");
      Require.nonNull(decoding, "decoding");
      Require.nonNull(aggregate, "aggregate");
      Require.nonNull(hardware, "hardware");
      return new AcceptanceReport(this, Require.nonEmptyCopy(seeds, "seeds"));
    }
  }
}
