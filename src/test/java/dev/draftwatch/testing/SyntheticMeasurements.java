package dev.draftwatch.testing;

import dev.draftwatch.domain.AcceptanceReport;
import dev.draftwatch.domain.Checkpoint;
import dev.draftwatch.domain.Measurement;
import dev.draftwatch.domain.Provenance;
import dev.draftwatch.harness.ReportParser;
import dev.draftwatch.stats.MetricCalculator;
import java.time.Instant;
import java.util.Map;

/**
 * Measurements built from fake-harness reports (synthetic counts from
 * {@code fixtures/synthetic_*.json}) with test-chosen provenance.
 */
public final class SyntheticMeasurements {
  private SyntheticMeasurements() {}

  /** Runs the fake harness for {@code s} and parses its report. */
  public static AcceptanceReport report(ReportScenario s) {
    if (s.run(Map.of()) != 0) {
      throw new IllegalStateException("fake harness failed in " + s.dir());
    }
    return new ReportParser(new MetricCalculator()).parse(s.reportPath(), s.expected());
  }

  /** A measurement of {@code s}'s probe on a checkpoint like {@code s}'s at {@code step}. */
  public static Measurement measurement(
      ReportScenario s, AcceptanceReport report, String jobId, long step, Instant end) {
    Checkpoint base = s.checkpoint();
    Checkpoint.Builder ckpt =
        Checkpoint.builder()
            .targetName(base.targetName())
            .path(base.path().resolveSibling("checkpoint-" + step))
            .step(step)
            .fingerprint("sampled-" + String.format("%064x", step))
            .type(base.type())
            .isFinal(false);
    base.baseModel().ifPresent(ckpt::baseModel);
    Provenance provenance =
        Provenance.builder()
            .checkpoint(ckpt.build())
            .probeId(s.probe().probe().id())
            .probeHash(s.probe().hash())
            .draftId(s.probe().probe().draft().id())
            .draftFingerprint(s.probe().draftFingerprint())
            .harnessVersion(report.harnessVersion())
            .backend(report.backend())
            .dtype(s.probe().probe().decoding().dtype())
            .estimator(s.probe().probe().estimator())
            .seeds(s.probe().probe().seeds())
            .promptSetSha256(s.probe().promptSet().sha256())
            .executor("fake")
            .jobId(jobId)
            .attempt(1)
            .startTime(end.minusSeconds(30))
            .endTime(end)
            .rawReportPath(s.reportPath())
            .build();
    return Measurement.of(provenance, report);
  }
}
