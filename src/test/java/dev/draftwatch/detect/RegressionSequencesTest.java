package dev.draftwatch.detect;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.draftwatch.config.AbsoluteDropSpec;
import dev.draftwatch.config.NoiseFloorSpec;
import dev.draftwatch.config.PairedBootstrapSpec;
import dev.draftwatch.config.TrendSpec;
import dev.draftwatch.domain.Measurement;
import dev.draftwatch.domain.Metric;
import dev.draftwatch.stats.MetricCalculator;
import dev.draftwatch.testing.CountsMeasurements;
import dev.draftwatch.testing.FakeHarness;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.junit.Test;

/**
 * ROADMAP M3: "synthetic sequences trigger exactly the expected detectors". Each scenario in
 * {@code fixtures/synthetic_regression_sequences.json} (invented counts) is four checkpoints;
 * the first is the baseline, the last is evaluated, and the set of detectors reporting
 * REGRESSION must equal the scenario's {@code expected} list.
 */
public class RegressionSequencesTest {
  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

  /** Detector kind (wire name) to detector, with the fixture's parameters. */
  private static Map<String, RegressionDetector> detectors(JsonNode d) {
    JsonNode b = d.get("paired_bootstrap");
    JsonNode a = d.get("absolute_drop");
    JsonNode n = d.get("noise_floor");
    JsonNode t = d.get("trend");
    Map<String, RegressionDetector> out = new TreeMap<>();
    out.put(
        "paired_bootstrap",
        new PairedBootstrapDetector(
            PairedBootstrapSpec.of(
                Metric.ALPHA,
                b.get("confidence").doubleValue(),
                b.get("min_effect").doubleValue(),
                b.get("resamples").intValue(),
                b.get("bootstrap_seed").longValue()),
            new MetricCalculator()));
    out.put(
        "absolute_drop",
        new AbsoluteDropDetector(
            AbsoluteDropSpec.of(Metric.ALPHA, a.get("max_drop").doubleValue())));
    out.put(
        "noise_floor",
        new NoiseFloorDetector(
            NoiseFloorSpec.of(
                Metric.ALPHA, n.get("k").doubleValue(), n.get("sigma").doubleValue())));
    out.put(
        "trend",
        new TrendDetector(
            TrendSpec.of(
                Metric.ALPHA, t.get("window").intValue(), t.get("max_slope").doubleValue())));
    return out;
  }

  @Test
  public void eachScenarioTriggersExactlyItsExpectedDetectors() throws IOException {
    JsonNode fixture =
        new ObjectMapper()
            .readTree(FakeHarness.fixture("synthetic_regression_sequences.json").toFile());
    Map<String, RegressionDetector> byKind = detectors(fixture.get("detectors"));
    DetectorSuite suite = new DetectorSuite(new ArrayList<>(byKind.values()));
    List<String> kinds = new ArrayList<>(byKind.keySet());
    CountsMeasurements builder =
        new CountsMeasurements(fixture.get("num_speculative_tokens").intValue());
    long steps = fixture.get("steps_per_prompt").longValue();
    long proposed = fixture.get("proposed_per_prompt").longValue();
    int scenarios = 0;
    for (Map.Entry<String, JsonNode> scenario : fixture.get("scenarios").properties()) {
      List<Measurement> history = new ArrayList<>();
      JsonNode checkpoints = scenario.getValue().get("accepted");
      for (int c = 0; c < checkpoints.size(); c++) {
        List<Integer> accepted = new ArrayList<>();
        checkpoints.get(c).forEach(v -> accepted.add(v.intValue()));
        history.add(
            builder.measurement(
                scenario.getKey() + "-" + c,
                100L * (c + 1),
                T0.plusSeconds(3600L * c),
                CountsMeasurements.prompts(steps, proposed, accepted)));
      }
      Measurement current = history.get(history.size() - 1);
      List<DetectorVerdict> verdicts = suite.run(current, Optional.of(history.get(0)), history);
      List<String> fired = new ArrayList<>();
      for (int i = 0; i < verdicts.size(); i++) {
        DetectorVerdict v = verdicts.get(i);
        assertTrue(
            scenario.getKey() + ": " + v,
            v.kind() == DetectorVerdict.Kind.OK || v.kind() == DetectorVerdict.Kind.REGRESSION);
        if (v.kind() == DetectorVerdict.Kind.REGRESSION) {
          fired.add(kinds.get(i));
        }
      }
      List<String> expected = new ArrayList<>();
      scenario.getValue().get("expected").forEach(v -> expected.add(v.textValue()));
      expected.sort(null);
      assertEquals(scenario.getKey() + " " + verdicts, expected, fired);
      scenarios++;
    }
    assertEquals(5, scenarios);
  }
}
