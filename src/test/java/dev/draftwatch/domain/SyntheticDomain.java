package dev.draftwatch.domain;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.OptionalDouble;

/**
 * Builders for structurally complete domain objects used by unit tests. Every number here is
 * invented; none is a measurement and none is internally consistent beyond what the class under
 * test checks.
 */
final class SyntheticDomain {
  static final String SHA_A = "a".repeat(64);
  static final String SHA_B = "b".repeat(64);

  private SyntheticDomain() {}

  static Decoding decoding() {
    return Decoding.of(BigDecimal.ZERO, 16, 2, "bfloat16");
  }

  static Checkpoint checkpoint() {
    return Checkpoint.builder()
        .targetName("run")
        .path(Paths.get("/ckpt/checkpoint-10"))
        .step(10)
        .fingerprint("sampled-" + SHA_A)
        .type(CheckpointType.FULL)
        .build();
  }

  static SeedReport seed(int seed) {
    return SeedReport.builder()
        .seed(seed)
        .alpha(0.5)
        .tau(2.0)
        .alphaByPosition(List.of(OptionalDouble.of(0.5), OptionalDouble.empty()))
        .totalSteps(2)
        .totalProposed(4)
        .totalAccepted(2)
        .excludedPrompts(0)
        .positionCountsExact(true)
        .perPrompt(List.of(PromptCounts.of(0, 2, 4, 2)))
        .positionCounts(List.of(PositionCount.of(1, 2, 1), PositionCount.of(2, 0, 0)))
        .build();
  }

  static AcceptanceReport.Builder report() {
    return AcceptanceReport.builder()
        .schemaVersion(1)
        .harnessVersion("0.1.0")
        .backend("fake")
        .adapterHandling(AdapterHandling.NONE)
        .draftStructure(DraftStructure.CHAIN)
        .estimator(Estimator.TOKEN_WEIGHTED)
        .draftId("draft")
        .promptSetSha256(SHA_B)
        .numPrompts(1)
        .decoding(decoding())
        .seeds(List.of(seed(0)))
        .aggregate(AggregateMetrics.of(0.5, OptionalDouble.empty(), 2.0, OptionalDouble.empty()))
        .hardware(Hardware.of("none", 0))
        .wallClockSeconds(1.0);
  }

  static Provenance.Builder provenance() {
    Path raw = Paths.get("/state/raw/job-1/report.json");
    return Provenance.builder()
        .checkpoint(checkpoint())
        .probeId("probe")
        .probeHash(SHA_A)
        .draftId("draft")
        .draftFingerprint("sampled-" + SHA_B)
        .harnessVersion("0.1.0")
        .backend("fake")
        .dtype("bfloat16")
        .estimator(Estimator.TOKEN_WEIGHTED)
        .seeds(List.of(0))
        .promptSetSha256(SHA_B)
        .executor("local")
        .jobId("job-1")
        .attempt(1)
        .startTime(Instant.parse("2026-01-01T00:00:00Z"))
        .endTime(Instant.parse("2026-01-01T00:01:00Z"))
        .rawReportPath(raw);
  }
}
