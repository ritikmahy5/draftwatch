package dev.draftwatch.report;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.draftwatch.detect.DetectorVerdict;
import dev.draftwatch.domain.AcceptanceReport;
import dev.draftwatch.domain.Measurement;
import dev.draftwatch.domain.Metric;
import dev.draftwatch.events.DetectionError;
import dev.draftwatch.events.DetectionEvent;
import dev.draftwatch.events.DetectionSubject;
import dev.draftwatch.store.DetectionRecord;
import dev.draftwatch.store.JsonCodec;
import dev.draftwatch.store.ResultPointers;
import dev.draftwatch.testing.ReportScenario;
import dev.draftwatch.testing.SyntheticMeasurements;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The report's content, built from synthetic stored results (DECISIONS.md D69). */
public class ReportModelTest {
  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
  private static final Path LOG = Paths.get("/state/detections.log");

  private final JsonCodec codec = new JsonCodec();
  private ReportScenario scenario;
  private AcceptanceReport report;

  @Before
  public void setUp() throws IOException {
    scenario = ReportScenario.greedy(Files.createDirectories(tmp.getRoot().toPath().resolve("s")));
    report = SyntheticMeasurements.report(scenario);
  }

  private Measurement m(String job, long step, Instant end) {
    return SyntheticMeasurements.measurement(scenario, report, job, step, end);
  }

  /** {@code m} stored under another target and harness version, through the codec. */
  private Measurement variant(Measurement m, String target, String harness) {
    ObjectNode json = codec.measurementJson(m);
    ((ObjectNode) json.get("provenance")).put("target", target).put("harness_version", harness);
    ((ObjectNode) json.get("report")).put("harness_version", harness);
    return codec.measurement(json);
  }

  private static Path file(Measurement m) {
    return Paths.get("/state/results", m.provenance().targetName(), m.jobId() + ".json");
  }

  private ReportModel build(List<String> configured, List<Measurement> ms,
      List<DetectionRecord> records) {
    return ReportModel.build(configured, ms, ReportModelTest::file, records, LOG);
  }

  private static List<Long> steps(ReportModel.Series s) {
    return s.rows().stream().map(ReportModel.Row::stepValue).collect(Collectors.toList());
  }

  @Test
  public void seriesAreSplitByTheComparabilityKey() {
    Measurement a = m("j1", 100, T0);
    Measurement b = m("j2", 200, T0.plusSeconds(1));
    Measurement c = variant(m("j3", 300, T0.plusSeconds(2)), "run", "0.2.0");
    ReportModel model = build(List.of("run"), List.of(c, b, a), List.of());
    List<ReportModel.Series> series = model.targets().get(0).series();
    assertEquals(2, series.size());
    assertEquals(List.of(100L, 200L), steps(series.get(0)));
    assertEquals(report.harnessVersion(), series.get(0).harnessVersion().text());
    assertEquals(List.of(300L), steps(series.get(1)));
    assertEquals("0.2.0", series.get(1).harnessVersion().text());
    assertEquals(3, model.resultCount());
  }

  @Test
  public void targetsComeInConfigOrderThenStoredOnesSorted() {
    List<Measurement> ms =
        List.of(
            variant(m("j1", 100, T0), "zeta", "0.1.0"),
            variant(m("j2", 100, T0), "beta", "0.1.0"),
            variant(m("j3", 100, T0), "alpha", "0.1.0"),
            variant(m("j4", 100, T0), "omega", "0.1.0"));
    ReportModel model = build(List.of("beta", "missing", "alpha"), ms, List.of());
    assertEquals(
        List.of("beta", "alpha", "omega", "zeta"),
        model.targets().stream().map(t -> t.name().text()).collect(Collectors.toList()));
    assertEquals(List.of("missing"), model.configuredWithoutResults());
  }

  @Test
  public void latestIsTheHighestStepThenTheLatestEndTime() {
    Measurement early = m("j-early", 300, T0.plusSeconds(10));
    Measurement late = m("j-late", 300, T0.plusSeconds(20));
    ReportModel.Series s =
        build(List.of("run"), List.of(late, m("j0", 200, T0), early), List.of())
            .targets().get(0).series().get(0);
    assertEquals(List.of(200L, 300L, 300L), steps(s));
    assertEquals("j-late", s.latest().row().jobId().text());
  }

  @Test
  public void valuesAreTracedExactlyToTheirPointers() {
    Measurement a = m("j1", 100, T0);
    ReportModel.Row row =
        build(List.of("run"), List.of(a), List.of()).targets().get(0).series().get(0).rows()
            .get(0);
    double alpha = a.report().aggregate().alphaMean();
    assertEquals(Traced.of(Double.toString(alpha), file(a), ResultPointers.ALPHA_MEAN),
        row.alphaMean());
    assertEquals(Traced.of("100", file(a), ResultPointers.CHECKPOINT_STEP), row.step());
    assertEquals(Optional.empty(), row.alphaStd());
    assertEquals(a.provenance().rawReportPath(), row.rawReport());
  }

  @Test
  public void positionalAcceptanceKeepsUndefinedPositionsAndTheExactFlag() {
    Measurement a = m("j1", 100, T0);
    ReportModel.Positional p =
        build(List.of("run"), List.of(a), List.of()).targets().get(0).series().get(0).latest();
    ReportModel.SeedPositions seed = p.seeds().get(0);
    assertEquals(a.report().seeds().get(0).positionCountsExact(), seed.exact());
    assertEquals(a.report().seeds().get(0).alphaByPosition().size(), seed.positions().size());
    for (int k = 0; k < seed.positions().size(); k++) {
      ReportModel.PositionValue v = seed.positions().get(k);
      assertEquals(Integer.toString(k + 1), v.position().text());
      assertEquals(a.report().seeds().get(0).alphaByPosition().get(k).isPresent(),
          v.alpha().isPresent());
      assertEquals(ResultPointers.position(0, k), v.position().pointer());
    }
  }

  @Test
  public void outcomesKeepKindsDetectorNamesAndMetricsButNoNumbers() {
    Measurement a = m("j1", 100, T0);
    DetectionSubject subject = DetectionSubject.of(a, file(a));
    List<DetectionRecord> records = new ArrayList<>();
    records.add(
        DetectionRecord.of(
            DetectionEvent.of(
                T0,
                subject,
                DetectorVerdict.builder(
                        DetectorVerdict.Kind.REGRESSION,
                        "paired_bootstrap(metric=alpha, confidence=0.95)",
                        Metric.ALPHA)
                    .observed(-0.07)
                    .threshold(-0.0)
                    .interval(-0.09, -0.05)
                    .explanation("95% interval …")
                    .build())));
    records.add(
        DetectionRecord.of(
            new DetectionError(
                T0,
                subject,
                DetectorVerdict.builder(DetectorVerdict.Kind.ERROR, "trend(window=4)", Metric.TAU)
                    .explanation("incomparable: harness_version")
                    .build())));
    ReportModel.Row row =
        build(List.of("run"), List.of(a), records).targets().get(0).series().get(0).rows().get(0);
    assertTrue(row.hasRegression());
    assertTrue(row.hasError());
    ReportModel.Outcome regression = row.outcomes().get(0);
    assertEquals("REGRESSION", regression.kind());
    assertEquals(Optional.of("paired_bootstrap"), regression.detector());
    assertEquals(Optional.of("alpha"), regression.metric());
    assertEquals(Optional.of("trend"), row.outcomes().get(1).detector());
  }
}
