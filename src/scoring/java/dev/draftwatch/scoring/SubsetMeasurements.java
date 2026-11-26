package dev.draftwatch.scoring;

import dev.draftwatch.domain.AcceptanceReport;
import dev.draftwatch.domain.AggregateMetrics;
import dev.draftwatch.domain.Measurement;
import dev.draftwatch.domain.PromptCounts;
import dev.draftwatch.domain.Provenance;
import dev.draftwatch.domain.SeedReport;
import dev.draftwatch.fingerprint.Sha256;
import dev.draftwatch.stats.MetricCalculator;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;

/**
 * Builds the measurement one probe subset would have given, from a full measurement's stored
 * per-prompt counts (docs/EVALUATION.md, "Scoring the detectors"). Totals, {@code alpha}, {@code
 * tau}, and the aggregate are recomputed by {@link MetricCalculator}; positional counts are left
 * empty, since they are not stored per prompt and no detector reads them. The subset gets its own
 * probe hash, prompt-set SHA-256, and job id, so it is comparable with itself across checkpoints
 * and with no other subset, and {@code trend} never drops it as a duplicate.
 */
public final class SubsetMeasurements {
  private final MetricCalculator calculator;

  public SubsetMeasurements(MetricCalculator calculator) {
    this.calculator = Objects.requireNonNull(calculator, "calculator");
  }

  /**
   * The subset of {@code full}, a validated one-seed measurement.
   *
   * @throws IllegalStateException if {@code alpha} or {@code tau} is undefined on the subset
   */
  public Measurement of(Measurement full, Subset subset) {
    AcceptanceReport r = full.report();
    SeedReport fullSeed = r.seeds().get(0);
    List<PromptCounts> chosen = new ArrayList<>(subset.size());
    long steps = 0;
    long proposed = 0;
    long accepted = 0;
    for (int i : subset.indices()) {
      PromptCounts p = fullSeed.perPrompt().get(i);
      chosen.add(p);
      steps += p.steps();
      proposed += p.proposed();
      accepted += p.accepted();
    }
    double alpha = defined(calculator.alpha(r.estimator(), chosen), "alpha", full, subset);
    double tau = defined(calculator.tau(r.estimator(), chosen), "tau", full, subset);
    String suffix = ":" + subset.size() + ":" + subset.ordinal();
    String probeHash = sha256Hex("subset-probe:" + full.probeHash() + suffix);
    String promptSet = sha256Hex("subset-prompts:" + r.promptSetSha256() + suffix);
    String jobId = full.jobId() + "-n" + subset.size() + "-s" + subset.ordinal();
    SeedReport seed =
        SeedReport.builder()
            .seed(fullSeed.seed())
            .alpha(alpha)
            .tau(tau)
            .alphaByPosition(List.of())
            .totalSteps(steps)
            .totalProposed(proposed)
            .totalAccepted(accepted)
            .excludedPrompts(calculator.excludedPrompts(r.estimator(), chosen))
            .positionCountsExact(false)
            .perPrompt(chosen)
            .positionCounts(List.of())
            .build();
    AcceptanceReport report =
        AcceptanceReport.builder()
            .schemaVersion(r.schemaVersion())
            .harnessVersion(r.harnessVersion())
            .backend(r.backend())
            .adapterHandling(r.adapterHandling())
            .draftStructure(r.draftStructure())
            .estimator(r.estimator())
            .draftId(r.draftId())
            .promptSetSha256(promptSet)
            .numPrompts(subset.size())
            .decoding(r.decoding())
            .seeds(List.of(seed))
            .aggregate(
                AggregateMetrics.of(alpha, OptionalDouble.empty(), tau, OptionalDouble.empty()))
            .hardware(r.hardware())
            .wallClockSeconds(r.wallClockSeconds())
            .build();
    Provenance p = full.provenance();
    Provenance provenance =
        Provenance.builder()
            .checkpoint(p.checkpoint())
            .probeId(p.probeId())
            .probeHash(probeHash)
            .draftId(p.draftId())
            .draftFingerprint(p.draftFingerprint())
            .harnessVersion(p.harnessVersion())
            .backend(p.backend())
            .dtype(p.dtype())
            .estimator(p.estimator())
            .seeds(p.seeds())
            .promptSetSha256(promptSet)
            .executor(p.executor())
            .jobId(jobId)
            .attempt(p.attempt())
            .startTime(p.startTime())
            .endTime(p.endTime())
            .rawReportPath(p.rawReportPath())
            .build();
    return Measurement.of(provenance, report);
  }

  private static double defined(OptionalDouble v, String what, Measurement full, Subset s) {
    if (v.isEmpty()) {
      throw new IllegalStateException(what + " is undefined on " + s + " of job " + full.jobId());
    }
    return v.getAsDouble();
  }

  /** SHA-256 of {@code text} as UTF-8, in lowercase hex. */
  static String sha256Hex(String text) {
    return Sha256.hex(text.getBytes(StandardCharsets.UTF_8));
  }
}
