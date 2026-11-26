package dev.draftwatch.scoring;

import static org.junit.Assert.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.draftwatch.config.PairedBootstrapSpec;
import dev.draftwatch.detect.DetectorVerdict;
import dev.draftwatch.detect.PairedBootstrapDetector;
import dev.draftwatch.domain.Measurement;
import dev.draftwatch.domain.Metric;
import dev.draftwatch.domain.PromptCounts;
import dev.draftwatch.stats.MetricCalculator;
import dev.draftwatch.testing.CountsMeasurements;
import dev.draftwatch.testing.FakeHarness;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.Test;

/** Labels from the reference probes, on {@code synthetic_scoring_reference_pair.json}. */
public class GroundTruthTest {
  private final MetricCalculator calc = new MetricCalculator();

  @Test
  public void labelBoundaries() {
    assertEquals(Label.REGRESSED, GroundTruth.label(-0.02, -0.03, -0.0101));
    assertEquals(Label.AMBIGUOUS, GroundTruth.label(-0.02, -0.03, -0.01));
    assertEquals(Label.AMBIGUOUS, GroundTruth.label(-0.0199, -0.03, -0.015));
    assertEquals(Label.NOT_REGRESSED, GroundTruth.label(-0.01, -0.0199, 0.0));
    assertEquals(Label.AMBIGUOUS, GroundTruth.label(-0.01, -0.02, 0.0));
    assertEquals(Label.NOT_REGRESSED, GroundTruth.label(0.03, 0.01, 0.05));
    assertEquals(0.02, GroundTruth.DELTA, 0.0);
  }

  @Test
  public void referenceMatchesPairedBootstrapDetector() throws IOException {
    JsonNode fixture =
        new ObjectMapper()
            .readTree(FakeHarness.fixture("synthetic_scoring_reference_pair.json").toFile());
    CountsMeasurements counts = new CountsMeasurements(4);
    Instant t = Instant.parse("2026-11-05T00:00:00Z");
    Measurement baseline = counts.measurement("j-base", 0, t, prompts(fixture.get("baseline")));
    Measurement current =
        counts.measurement("j-cur", 100, t.plusSeconds(60), prompts(fixture.get("current")));
    DetectorVerdict verdict =
        new PairedBootstrapDetector(PairedBootstrapSpec.withDefaults(Metric.ALPHA), calc)
            .evaluate(current, Optional.of(baseline), List.of());
    ReferenceDifference ref = new GroundTruth(calc).reference(current, baseline);
    assertEquals(verdict.observed().getAsDouble(), ref.delta(), 0.0);
    assertEquals(verdict.intervalLower().getAsDouble(), ref.lower(), 0.0);
    assertEquals(verdict.intervalUpper().getAsDouble(), ref.upper(), 0.0);
    assertEquals(GroundTruth.label(ref.delta(), ref.lower(), ref.upper()), ref.label());
  }

  private static List<PromptCounts> prompts(JsonNode rows) {
    List<PromptCounts> out = new ArrayList<>();
    for (int i = 0; i < rows.size(); i++) {
      JsonNode r = rows.get(i);
      out.add(PromptCounts.of(i, r.get(0).asLong(), r.get(1).asLong(), r.get(2).asLong()));
    }
    return out;
  }
}
