package dev.draftwatch.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import dev.draftwatch.config.ConfigValidator;
import dev.draftwatch.config.DraftwatchConfig;
import dev.draftwatch.domain.Baseline;
import dev.draftwatch.domain.Checkpoint;
import dev.draftwatch.domain.CheckpointType;
import dev.draftwatch.domain.Measurement;
import dev.draftwatch.domain.Provenance;
import dev.draftwatch.events.DetectionEvent;
import dev.draftwatch.events.EventBus;
import dev.draftwatch.events.MeasurementStored;
import dev.draftwatch.stats.MetricCalculator;
import dev.draftwatch.store.DetectionRecord;
import dev.draftwatch.testing.CountsMeasurements;
import dev.draftwatch.testing.InMemoryBaselineRepository;
import dev.draftwatch.testing.InMemoryDetectionLog;
import dev.draftwatch.testing.InMemoryResultRepository;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Before;
import org.junit.Test;

/** Baseline choice and idempotence, on invented counts (10 prompts, 40 proposed each). */
public class DetectionServiceTest {
  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

  private final InMemoryResultRepository results = new InMemoryResultRepository();
  private final InMemoryBaselineRepository baselines = new InMemoryBaselineRepository();
  private final InMemoryDetectionLog log = new InMemoryDetectionLog();
  private final List<String> diagnostics = new ArrayList<>();
  private final EventBus bus = new EventBus(diagnostics::add, Clock.systemUTC());
  private DetectionService service;

  @Before
  public void setUp() throws Exception {
    String yaml =
        String.join(
            "\n",
            "executor: { type: local, max_retries: 0 }",
            "harness: { command: [h] }",
            "probes:",
            "  - { id: probe, draft: { id: d, path: d, structure: chain },"
                + " prompts: { path: p.jsonl }, decoding: { temperature: 0, max_new_tokens: 64,"
                + " num_speculative_tokens: 4, dtype: bfloat16 }, seeds: [0] }",
            "targets:",
            "  - { name: run, checkpoint_dirs: [r], checkpoint_type: full, probes: [probe],",
            "      detectors: [{absolute_drop: {metric: alpha, max_drop: 0.05}}] }");
    ObjectMapper mapper = new YAMLMapper();
    DraftwatchConfig config =
        new ConfigValidator().validate(mapper.readTree(yaml), Paths.get("/w/draftwatch.yaml"));
    MetricCalculator calc = new MetricCalculator();
    service =
        new DetectionService(
            config,
            t -> Bootstrap.suite(t, calc),
            results,
            baselines,
            log,
            bus,
            Clock.systemUTC());
    bus.subscribe(DetectionEvent.class, "log", e -> log.append(DetectionRecord.of(e)));
  }

  private Measurement store(CountsMeasurements b, String job, long step, int accepted) {
    List<Integer> counts = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      counts.add(accepted);
    }
    Measurement m =
        b.measurement(
            job, step, T0.plusSeconds(step), CountsMeasurements.prompts(10, 40, counts));
    results.append(m);
    return m;
  }

  private void publish(Measurement m) {
    service.onMeasurementStored(new MeasurementStored(T0, m, results.locate(m)));
  }

  private List<String> kindsOf(String job) {
    return log.all().stream()
        .filter(r -> r.jobId().equals(job))
        .map(DetectionRecord::kind)
        .collect(Collectors.toList());
  }

  @Test
  public void publishingTheSameMeasurementTwiceRecordsEachDetectorOnce() {
    CountsMeasurements b = new CountsMeasurements(4);
    publish(store(b, "j1", 100, 28));
    Measurement m = store(b, "j2", 200, 20);
    publish(m);
    publish(m);
    assertEquals(List.of("REGRESSION"), kindsOf("j2"));
  }

  @Test
  public void latestComparableBaselineResultIsUsed() {
    String fingerprint = "sampled-" + String.format("%064x", 100);
    baselines.set(
        Baseline.of("run", fingerprint, Paths.get("/r/c"), 100, T0, Baseline.Source.MANUAL));
    store(new CountsMeasurements(4), "old", 100, 28);
    store(new CountsMeasurements(4).harnessVersion("fake-9"), "newer-incomparable", 100, 10);
    publish(store(new CountsMeasurements(4), "j2", 200, 27));
    DetectionRecord r = log.all().get(log.all().size() - 1);
    assertEquals("OK", r.kind());
    assertEquals("old", r.baselineJobId().get());
  }

  /** DECISIONS.md D89, D92: measuring the baseline checkpoint again on the new GPU suffices. */
  @Test
  public void anotherGpuIsAnErrorUntilTheBaselineIsMeasuredOnIt() {
    CountsMeasurements a100 = new CountsMeasurements(4).hardware("NVIDIA A100-SXM4-80GB", 1);
    CountsMeasurements h200 = new CountsMeasurements(4).hardware("NVIDIA H200", 1);
    publish(store(a100, "base-a100", 100, 28));
    publish(store(h200, "j2", 200, 27));
    assertEquals(List.of("ERROR"), kindsOf("j2"));
    assertEquals("incomparable: hardware", log.all().get(log.all().size() - 1).explanation());
    publish(store(h200, "base-h200", 100, 28));
    assertEquals(List.of("ERROR"), kindsOf("base-h200"));
    publish(store(h200, "j3", 300, 27));
    DetectionRecord r = log.all().get(log.all().size() - 1);
    assertEquals("OK", r.kind());
    assertEquals("base-h200", r.baselineJobId().get());
  }

  @Test
  public void unconfiguredTargetFailsLoudlyThroughTheBus() {
    bus.subscribe(MeasurementStored.class, "detection", service::onMeasurementStored);
    Measurement m = store(new CountsMeasurements(4), "j1", 100, 28);
    Measurement other =
        Measurement.of(
            Provenance.builder()
                .checkpoint(
                    Checkpoint.builder()
                        .targetName("gone")
                        .path(Paths.get("/x"))
                        .step(1)
                        .fingerprint("sampled-x")
                        .type(CheckpointType.FULL)
                        .build())
                .probeId("probe")
                .probeHash(m.probeHash())
                .draftId("draft")
                .draftFingerprint("sampled-d")
                .harnessVersion("fake-0.1.0")
                .backend("fake")
                .dtype("bfloat16")
                .estimator(m.provenance().estimator())
                .seeds(List.of(0))
                .promptSetSha256(m.provenance().promptSetSha256())
                .executor("fake")
                .jobId("j9")
                .attempt(1)
                .startTime(T0)
                .endTime(T0)
                .rawReportPath(Paths.get("/r"))
                .build(),
            m.report());
    bus.publish(new MeasurementStored(T0, other, Paths.get("/r.json")));
    assertEquals(1, diagnostics.size());
    assertTrue(diagnostics.get(0), diagnostics.get(0).contains("target gone is not configured"));
  }
}
