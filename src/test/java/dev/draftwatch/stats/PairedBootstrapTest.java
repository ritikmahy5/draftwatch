package dev.draftwatch.stats;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.draftwatch.domain.Estimator;
import dev.draftwatch.domain.PromptCounts;
import dev.draftwatch.testing.FakeHarness;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.function.Function;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Exact-output tests of the paired bootstrap on {@code fixtures/synthetic_bootstrap_pair.json}
 * (invented counts). Expected intervals come from {@code scripts/bootstrap_reference.py}, an
 * independent implementation, both as recorded values and by running it.
 */
public class PairedBootstrapTest {
  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private final ObjectMapper json = new ObjectMapper();
  private final MetricCalculator calc = new MetricCalculator();
  private JsonNode fixture;
  private List<PromptCounts> baseline;
  private List<PromptCounts> current;

  @Before
  public void setUp() throws IOException {
    fixture = json.readTree(FakeHarness.fixture("synthetic_bootstrap_pair.json").toFile());
    baseline = prompts(fixture.get("baseline"));
    current = prompts(fixture.get("current"));
  }

  private static List<PromptCounts> prompts(JsonNode rows) {
    List<PromptCounts> out = new ArrayList<>();
    for (int i = 0; i < rows.size(); i++) {
      JsonNode r = rows.get(i);
      out.add(
          PromptCounts.of(i, r.get(0).longValue(), r.get(1).longValue(), r.get(2).longValue()));
    }
    return out;
  }

  private Function<List<PromptCounts>, OptionalDouble> statistic(Estimator e, String metric) {
    return metric.equals("alpha") ? p -> calc.alpha(e, p) : p -> calc.tau(e, p);
  }

  private BootstrapInterval run(Estimator e, String metric, long seed) {
    return PairedBootstrap.run(current, baseline, statistic(e, metric), 2000, 0.95, seed);
  }

  /** Runs the reference script on the same inputs and returns its interval. */
  private BootstrapInterval reference(Estimator e, String metric, long seed) throws Exception {
    ObjectNode spec = json.createObjectNode();
    spec.set("current", fixture.get("current"));
    spec.set("baseline", fixture.get("baseline"));
    spec.put("estimator", e.wireName());
    spec.put("metric", metric);
    spec.put("resamples", 2000);
    spec.put("confidence", 0.95);
    spec.put("seed", seed);
    Path input = tmp.newFile().toPath();
    json.writeValue(input.toFile(), spec);
    Path script = FakeHarness.projectDir().resolve("scripts/bootstrap_reference.py");
    Path out = tmp.newFile().toPath();
    List<String> command = List.of("python3", script.toString(), input.toString());
    int exit = FakeHarness.run(command, tmp.getRoot().toPath(), out);
    assertEquals(Files.readString(out), 0, exit);
    JsonNode r = json.readTree(Files.readString(out));
    return new BootstrapInterval(
        Double.parseDouble(r.get("observed").textValue()),
        Double.parseDouble(r.get("lower").textValue()),
        Double.parseDouble(r.get("upper").textValue()),
        0.95,
        2000,
        seed);
  }

  // --- identical intervals for identical inputs and seed ----------------------------------------

  @Test
  public void identicalInputsAndSeedGiveIdenticalIntervals() {
    BootstrapInterval first = run(Estimator.TOKEN_WEIGHTED, "alpha", 0);
    assertEquals(first, run(Estimator.TOKEN_WEIGHTED, "alpha", 0));
    assertNotEquals(first, run(Estimator.TOKEN_WEIGHTED, "alpha", 1));
  }

  @Test
  public void matchesTheIndependentReferenceExactly() throws Exception {
    assertEquals(
        reference(Estimator.TOKEN_WEIGHTED, "alpha", 0), run(Estimator.TOKEN_WEIGHTED, "alpha", 0));
    assertEquals(
        reference(Estimator.SIMPLE_MEAN, "tau", 7), run(Estimator.SIMPLE_MEAN, "tau", 7));
  }

  @Test
  public void recordedReferenceValuesAreReproduced() {
    // Recorded from scripts/bootstrap_reference.py on 2026-10-01 (Python 3.13.9).
    BootstrapInterval alpha = run(Estimator.TOKEN_WEIGHTED, "alpha", 0);
    assertEquals(-0.07051282051282048, alpha.observed(), 0.0);
    assertEquals(-0.08901803359683802, alpha.lower(), 0.0);
    assertEquals(-0.050438135780628036, alpha.upper(), 0.0);
    BootstrapInterval tau = run(Estimator.SIMPLE_MEAN, "tau", 7);
    assertEquals(-0.27099035224035317, tau.observed(), 0.0);
    assertEquals(-0.34804422429422444, tau.lower(), 0.0);
    assertEquals(-0.1898829237891733, tau.upper(), 0.0);
  }

  @Test
  public void observedIsTheFullDataDifference() {
    // fixtures/synthetic_bootstrap_pair.json: accepted 278 vs 311, proposed 468 on both sides.
    double observed = run(Estimator.TOKEN_WEIGHTED, "alpha", 0).observed();
    assertEquals(278.0 / 468.0 - 311.0 / 468.0, observed, 0.0);
  }

  @Test
  public void identicalSidesGiveAZeroInterval() {
    BootstrapInterval same =
        PairedBootstrap.run(
            baseline, baseline, statistic(Estimator.TOKEN_WEIGHTED, "alpha"), 500, 0.9, 3);
    assertEquals(0.0, same.observed(), 0.0);
    assertEquals(0.0, same.lower(), 0.0);
    assertEquals(0.0, same.upper(), 0.0);
  }

  @Test
  public void undefinedStatisticFailsInsteadOfDroppingDraws() {
    List<PromptCounts> nothing = List.of(PromptCounts.of(0, 3, 0, 0), PromptCounts.of(1, 2, 4, 1));
    try {
      PairedBootstrap.run(
          nothing, nothing, statistic(Estimator.TOKEN_WEIGHTED, "alpha"), 200, 0.95, 0);
      fail("expected UndefinedStatisticException");
    } catch (UndefinedStatisticException e) {
      String prefix = "the statistic is undefined on bootstrap resample ";
      assertTrue(e.getMessage(), e.getMessage().startsWith(prefix));
    }
  }

  @Test(expected = IllegalArgumentException.class)
  public void unpairedListsAreRejected() {
    PairedBootstrap.run(
        current, baseline.subList(0, 3), statistic(Estimator.TOKEN_WEIGHTED, "alpha"), 10, 0.9, 0);
  }

  @Test
  public void quantileInterpolatesLinearlyBetweenOrderStatistics() {
    double[] x = {1, 2, 4, 8};
    assertEquals(1.0, PairedBootstrap.quantile(x, 0.0), 0.0);
    assertEquals(8.0, PairedBootstrap.quantile(x, 1.0), 0.0);
    assertEquals(3.0, PairedBootstrap.quantile(x, 0.5), 0.0); // h = 1.5: 2 + 0.5 * (4 - 2)
    assertEquals(2.0 + (2.0 / 3.0) * 2.0, PairedBootstrap.quantile(x, 5.0 / 9.0), 1e-15);
  }
}
