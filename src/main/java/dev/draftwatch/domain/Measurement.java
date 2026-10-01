package dev.draftwatch.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One execution of a probe against one checkpoint: the validated report together with its
 * provenance. Results are append-only; a re-measurement is a new {@code Measurement} with a new
 * job id.
 */
public final class Measurement {
  private final Provenance provenance;
  private final AcceptanceReport report;

  private Measurement(Provenance provenance, AcceptanceReport report) {
    this.provenance = provenance;
    this.report = report;
  }

  /**
   * Pairs a report with its provenance.
   *
   * @throws IllegalArgumentException naming the first field that the report and the
   *     provenance both record but disagree on; a mismatch means they describe different runs
   */
  public static Measurement of(Provenance provenance, AcceptanceReport report) {
    Require.nonNull(provenance, "provenance");
    Require.nonNull(report, "report");
    requireSame("harness_version", provenance.harnessVersion(), report.harnessVersion());
    requireSame("backend", provenance.backend(), report.backend());
    requireSame("estimator", provenance.estimator(), report.estimator());
    requireSame("draft_id", provenance.draftId(), report.draftId());
    requireSame("prompt_set_sha256", provenance.promptSetSha256(), report.promptSetSha256());
    requireSame("dtype", provenance.dtype(), report.decoding().dtype());
    List<Integer> reportSeeds = new ArrayList<>();
    for (SeedReport seed : report.seeds()) {
      reportSeeds.add(seed.seed());
    }
    requireSame("seeds", provenance.seeds(), reportSeeds);
    return new Measurement(provenance, report);
  }

  private static void requireSame(String field, Object inProvenance, Object inReport) {
    if (!inProvenance.equals(inReport)) {
      throw new IllegalArgumentException(
          field + " differs: provenance " + inProvenance + ", report " + inReport);
    }
  }

  public Provenance provenance() {
    return provenance;
  }

  public AcceptanceReport report() {
    return report;
  }

  public String fingerprint() {
    return provenance.checkpoint().fingerprint();
  }

  public String probeHash() {
    return provenance.probeHash();
  }

  public String jobId() {
    return provenance.jobId();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Measurement)) {
      return false;
    }
    Measurement that = (Measurement) o;
    return provenance.equals(that.provenance) && report.equals(that.report);
  }

  @Override
  public int hashCode() {
    return Objects.hash(provenance, report);
  }

  @Override
  public String toString() {
    return "Measurement{" + provenance + "}";
  }
}
