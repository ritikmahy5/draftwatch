package dev.draftwatch.harness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.draftwatch.domain.AcceptanceReport;
import dev.draftwatch.domain.AdapterHandling;
import dev.draftwatch.domain.Estimator;
import dev.draftwatch.domain.PromptCounts;
import dev.draftwatch.domain.SeedReport;
import dev.draftwatch.stats.MetricCalculator;
import dev.draftwatch.testing.ReportScenario;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Valid reports from the fake harness (synthetic counts from {@code fixtures/synthetic_*.json})
 * and hand-edited variants for cases the fake cannot produce.
 */
public class ReportParserTest {
  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private final ObjectMapper json = new ObjectMapper();
  private final ReportParser parser = new ReportParser(new MetricCalculator());

  private ReportScenario run(ReportScenario s) {
    assertEquals(0, s.run(Map.of()));
    return s;
  }

  private AcceptanceReport parse(ReportScenario s) {
    return parser.parse(s.reportPath(), s.expected());
  }

  private ObjectNode tree(ReportScenario s) throws IOException {
    return (ObjectNode) json.readTree(s.reportPath().toFile());
  }

  private void write(ReportScenario s, JsonNode tree) throws IOException {
    json.writeValue(s.reportPath().toFile(), tree);
  }

  private ReportViolationException rejection(ReportScenario s) {
    try {
      parse(s);
      fail("expected ReportViolationException");
      return null;
    } catch (ReportViolationException e) {
      return e;
    }
  }

  // --- valid reports -----------------------------------------------------------------------

  @Test
  public void greedyReportIsReturnedExactlyAsWritten() {
    ReportScenario s = run(ReportScenario.greedy(tmp.getRoot().toPath()));
    AcceptanceReport r = parse(s);
    assertEquals("fake-0.1.0", r.harnessVersion());
    assertEquals("fake", r.backend());
    assertEquals(AdapterHandling.NONE, r.adapterHandling());
    assertEquals(Estimator.TOKEN_WEIGHTED, r.estimator());
    assertEquals(s.probe().promptSet().sha256(), r.promptSetSha256());
    assertEquals(3, r.numPrompts());
    assertEquals(s.probe().probe().decoding(), r.decoding());
    SeedReport seed = r.seeds().get(0);
    // fixtures/synthetic_three_prompts.json: accepted 12, proposed 26, steps 9.
    assertEquals(12.0 / 26.0, seed.alpha(), 0.0);
    assertEquals((12.0 + 9.0) / 9.0, seed.tau(), 0.0);
    assertEquals(
        List.of(
            PromptCounts.of(0, 4, 12, 7), PromptCounts.of(1, 2, 6, 2), PromptCounts.of(2, 3, 8, 3)),
        seed.perPrompt());
    assertEquals(
        List.of(
            OptionalDouble.of(6.0 / 9.0),
            OptionalDouble.of(4.0 / 6.0),
            OptionalDouble.of(2.0 / 4.0)),
        seed.alphaByPosition());
    assertFalse(seed.positionCountsExact());
    assertEquals(OptionalDouble.empty(), r.aggregate().alphaStd());
  }

  @Test
  public void sampledAdapterReportWithTwoSeedsIsValid() {
    ReportScenario s = run(ReportScenario.sampledAdapter(tmp.getRoot().toPath()));
    AcceptanceReport r = parse(s);
    assertEquals(AdapterHandling.MERGED, r.adapterHandling());
    assertEquals(List.of(3, 5), List.of(r.seeds().get(0).seed(), r.seeds().get(1).seed()));
    assertTrue(r.aggregate().alphaStd().isPresent());
    assertTrue(r.aggregate().tauStd().isPresent());
  }

  @Test
  public void excludedPromptIsCountedOnlyBySimpleMean() {
    ReportScenario simple =
        run(ReportScenario.excludedPrompt(tmp.getRoot().toPath(), Estimator.SIMPLE_MEAN));
    assertEquals(1, parse(simple).seeds().get(0).excludedPrompts());
    ReportScenario weighted =
        run(ReportScenario.excludedPrompt(tmp.getRoot().toPath(), Estimator.TOKEN_WEIGHTED));
    assertEquals(0, parse(weighted).seeds().get(0).excludedPrompts());
  }

  @Test
  public void reportJsonRoundTripsExactly() {
    ReportScenario s = run(ReportScenario.sampledAdapter(tmp.getRoot().toPath()));
    AcceptanceReport r = parse(s);
    assertEquals(r, ReportJson.toReport(ReportJson.toJson(r)));
  }

  // --- tolerance ---------------------------------------------------------------------------

  @Test
  public void differencesWithinToleranceAreAcceptedAndBeyondAreNot() throws IOException {
    ReportScenario s = run(ReportScenario.greedy(tmp.getRoot().toPath()));
    ObjectNode t = tree(s);
    ObjectNode seed = (ObjectNode) t.get("seeds").get(0);
    ObjectNode aggregate = (ObjectNode) t.get("aggregate");
    double alpha = seed.get("alpha").doubleValue();
    seed.put("alpha", alpha + 5e-10);
    aggregate.put("alpha_mean", alpha + 5e-10);
    write(s, t);
    assertEquals(alpha + 5e-10, parse(s).seeds().get(0).alpha(), 0.0);
    seed.put("alpha", alpha + 2e-9);
    aggregate.put("alpha_mean", alpha + 2e-9);
    write(s, t);
    assertEquals(ReportRule.ALPHA, rejection(s).rule());
  }

  // --- cases the fake cannot produce -------------------------------------------------------

  @Test
  public void undefinedAlphaIsRejected() throws IOException {
    ReportScenario s = run(ReportScenario.greedy(tmp.getRoot().toPath()));
    ObjectNode t = tree(s);
    ObjectNode seed = (ObjectNode) t.get("seeds").get(0);
    for (JsonNode p : seed.get("per_prompt")) {
      ((ObjectNode) p).put("proposed", 0).put("accepted", 0);
    }
    for (JsonNode p : seed.get("position_counts")) {
      ((ObjectNode) p).put("eligible", 0).put("accepted", 0);
    }
    ArrayNode byPosition = seed.putArray("alpha_by_position");
    byPosition.addNull().addNull().addNull();
    seed.put("total_proposed", 0).put("total_accepted", 0).put("alpha", 0.0).put("tau", 1.0);
    ((ObjectNode) t.get("aggregate")).put("alpha_mean", 0.0).put("tau_mean", 1.0);
    write(s, t);
    ReportViolationException e = rejection(s);
    assertEquals(ReportRule.ALPHA, e.rule());
    assertTrue(e.getMessage(), e.getMessage().contains("undefined"));
  }

  @Test
  public void repeatedKeyIsInvalidJson() throws IOException {
    ReportScenario s = run(ReportScenario.greedy(tmp.getRoot().toPath()));
    String text = Files.readString(s.reportPath());
    Files.writeString(s.reportPath(), text.replaceFirst("\\{", "{\"backend\": \"x\", "));
    assertEquals(ReportRule.JSON, rejection(s).rule());
  }

  @Test
  public void trailingContentIsInvalidJson() throws IOException {
    ReportScenario s = run(ReportScenario.greedy(tmp.getRoot().toPath()));
    Files.writeString(s.reportPath(), Files.readString(s.reportPath()) + "{}");
    assertEquals(ReportRule.JSON, rejection(s).rule());
  }

  @Test
  public void emptyFileIsInvalidJson() throws IOException {
    ReportScenario s = run(ReportScenario.greedy(tmp.getRoot().toPath()));
    Files.writeString(s.reportPath(), "");
    assertEquals(ReportRule.JSON, rejection(s).rule());
  }

  @Test
  public void unknownKeyBreaksShape() throws IOException {
    ReportScenario s = run(ReportScenario.greedy(tmp.getRoot().toPath()));
    ObjectNode t = tree(s);
    ((ObjectNode) t.get("seeds").get(0)).put("p_value", 0.01);
    write(s, t);
    ReportViolationException e = rejection(s);
    assertEquals(ReportRule.SHAPE, e.rule());
    assertTrue(e.getMessage(), e.getMessage().contains("seeds[0].p_value is not in the schema"));
  }

  @Test
  public void wrongTypeBreaksShapeNamingTheField() throws IOException {
    ReportScenario s = run(ReportScenario.greedy(tmp.getRoot().toPath()));
    ObjectNode t = tree(s);
    ((ObjectNode) t.get("seeds").get(0).get("per_prompt").get(1)).put("steps", 2.5);
    write(s, t);
    ReportViolationException e = rejection(s);
    assertEquals(ReportRule.SHAPE, e.rule());
    assertTrue(e.getMessage(), e.getMessage().contains("seeds[0].per_prompt[1].steps"));
  }

  @Test
  public void reportIsNeverRepaired() throws IOException {
    ReportScenario s = run(ReportScenario.greedy(tmp.getRoot().toPath()));
    byte[] before = Files.readAllBytes(s.reportPath());
    parse(s);
    assertEquals(new String(before), Files.readString(s.reportPath()));
  }
}
