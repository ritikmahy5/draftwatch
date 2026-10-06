package dev.draftwatch.testing;

import dev.draftwatch.domain.AcceptanceReport;
import dev.draftwatch.domain.AdapterHandling;
import dev.draftwatch.domain.AggregateMetrics;
import dev.draftwatch.domain.Checkpoint;
import dev.draftwatch.domain.CheckpointType;
import dev.draftwatch.domain.Decoding;
import dev.draftwatch.domain.DraftStructure;
import dev.draftwatch.domain.Estimator;
import dev.draftwatch.domain.Hardware;
import dev.draftwatch.domain.Measurement;
import dev.draftwatch.domain.PositionCount;
import dev.draftwatch.domain.PromptCounts;
import dev.draftwatch.domain.Provenance;
import dev.draftwatch.domain.SeedReport;
import dev.draftwatch.stats.MetricCalculator;
import java.math.BigDecimal;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;

/**
 * Builds measurements straight from invented per-prompt counts, with every derived number
 * (totals, alpha, tau, positional acceptance, aggregates) computed by {@link MetricCalculator},
 * so they are internally consistent. For detector tests that need many checkpoints.
 */
public final class CountsMeasurements {
  public static final String PROBE_HASH = "e".repeat(64);
  private static final MetricCalculator CALC = new MetricCalculator();

  private final int k;
  private String harnessVersion = "fake-0.1.0";
  private String backend = "fake";
  private String probeHash = PROBE_HASH;
  private Hardware hardware = Hardware.of("none", 0);

  public CountsMeasurements(int numSpeculativeTokens) {
    this.k = numSpeculativeTokens;
  }

  public CountsMeasurements harnessVersion(String version) {
    this.harnessVersion = version;
    return this;
  }

  public CountsMeasurements backend(String backend) {
    this.backend = backend;
    return this;
  }

  public CountsMeasurements hardware(String gpu, int count) {
    this.hardware = Hardware.of(gpu, count);
    return this;
  }

  public CountsMeasurements probeHash(String hash) {
    this.probeHash = hash;
    return this;
  }

  /** Prompts with the same steps and proposed counts and the given accepted counts. */
  public static List<PromptCounts> prompts(long steps, long proposed, List<Integer> accepted) {
    List<PromptCounts> out = new ArrayList<>();
    for (int i = 0; i < accepted.size(); i++) {
      out.add(PromptCounts.of(i, steps, proposed, accepted.get(i)));
    }
    return out;
  }

  /** A single-seed, token-weighted measurement of checkpoint {@code step}, job {@code jobId}. */
  public Measurement measurement(
      String jobId, long step, Instant end, List<PromptCounts> prompts) {
    long steps = 0;
    long proposed = 0;
    long accepted = 0;
    boolean exact = true;
    for (PromptCounts p : prompts) {
      steps += p.steps();
      proposed += p.proposed();
      accepted += p.accepted();
      exact &= p.proposed() == p.steps() * k;
    }
    List<PositionCount> positions = positions(steps, accepted);
    double alpha = CALC.alpha(Estimator.TOKEN_WEIGHTED, prompts).getAsDouble();
    double tau = CALC.tau(Estimator.TOKEN_WEIGHTED, prompts).getAsDouble();
    SeedReport seed =
        SeedReport.builder()
            .seed(0)
            .alpha(alpha)
            .tau(tau)
            .alphaByPosition(CALC.alphaByPosition(positions))
            .totalSteps(steps)
            .totalProposed(proposed)
            .totalAccepted(accepted)
            .excludedPrompts(0)
            .positionCountsExact(exact)
            .perPrompt(prompts)
            .positionCounts(positions)
            .build();
    AcceptanceReport report =
        AcceptanceReport.builder()
            .schemaVersion(1)
            .harnessVersion(harnessVersion)
            .backend(backend)
            .adapterHandling(AdapterHandling.NONE)
            .draftStructure(DraftStructure.CHAIN)
            .estimator(Estimator.TOKEN_WEIGHTED)
            .draftId("draft")
            .promptSetSha256("f".repeat(64))
            .numPrompts(prompts.size())
            .decoding(Decoding.of(BigDecimal.ZERO, 64, k, "bfloat16"))
            .seeds(List.of(seed))
            .aggregate(
                AggregateMetrics.of(alpha, OptionalDouble.empty(), tau, OptionalDouble.empty()))
            .hardware(hardware)
            .wallClockSeconds(1.0)
            .build();
    Checkpoint checkpoint =
        Checkpoint.builder()
            .targetName("run")
            .path(Paths.get("/runs/checkpoint-" + step))
            .step(step)
            .fingerprint("sampled-" + String.format("%064x", step))
            .type(CheckpointType.FULL)
            .build();
    Provenance provenance =
        Provenance.builder()
            .checkpoint(checkpoint)
            .probeId("probe")
            .probeHash(probeHash)
            .draftId("draft")
            .draftFingerprint("sampled-" + "d".repeat(64))
            .harnessVersion(harnessVersion)
            .backend(backend)
            .dtype("bfloat16")
            .estimator(Estimator.TOKEN_WEIGHTED)
            .seeds(List.of(0))
            .promptSetSha256("f".repeat(64))
            .executor("fake")
            .jobId(jobId)
            .attempt(1)
            .startTime(end.minusSeconds(60))
            .endTime(end)
            .rawReportPath(Paths.get("/state/raw/" + jobId + "/attempt-1/report.json"))
            .build();
    return Measurement.of(provenance, report);
  }

  /** Prefix-consistent pooled positional counts: fill positions greedily, front to back. */
  private List<PositionCount> positions(long steps, long accepted) {
    List<PositionCount> out = new ArrayList<>();
    long eligible = steps;
    long remaining = accepted;
    for (int j = 1; j <= k; j++) {
      long acc = Math.min(eligible, remaining);
      out.add(PositionCount.of(j, eligible, acc));
      remaining -= acc;
      eligible = acc;
    }
    return out;
  }
}
