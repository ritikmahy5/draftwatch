package dev.draftwatch.scoring;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import dev.draftwatch.domain.Baseline;
import dev.draftwatch.domain.Measurement;
import dev.draftwatch.domain.PromptCounts;
import dev.draftwatch.testing.CountsMeasurements;
import dev.draftwatch.testing.InMemoryBaselineRepository;
import dev.draftwatch.testing.InMemoryResultRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.Before;
import org.junit.Test;

/** Loading and validating stored results. Every count here is synthetic. */
public class EvaluationDataTest {
  private static final Instant T0 = Instant.parse("2026-11-05T00:00:00Z");
  private static final List<PromptCounts> PROMPTS =
      CountsMeasurements.prompts(10, 40, List.of(30, 31, 32, 33));

  private InMemoryResultRepository results;
  private InMemoryBaselineRepository baselines;
  private CountsMeasurements counts;

  @Before
  public void setUp() {
    results = new InMemoryResultRepository();
    baselines = new InMemoryBaselineRepository();
    counts = new CountsMeasurements(4);
  }

  /** Every expected measurement of family {@code qwen}, except those in {@code skip}. */
  private void fullFamily(Set<String> skip) {
    for (ProbeKind kind : ProbeKind.values()) {
      for (Run run : Run.values()) {
        List<Long> steps = run == Run.RUN1 ? ExpectedSteps.RUN1 : ExpectedSteps.RUN2;
        for (long step : steps) {
          if (!skip.contains(key("qwen-" + run.suffix(), "qwen-" + kind.suffix(), step))) {
            results.append(m("qwen", run, kind, step, PROMPTS));
          }
        }
      }
    }
    pinBaselines();
  }

  private void pinBaselines() {
    Measurement zero = results.all().stream()
        .filter(x -> x.provenance().targetName().equals("qwen-run1")
            && x.provenance().checkpoint().step() == 0)
        .findFirst()
        .get();
    baselines.set(Baseline.of(zero.provenance().checkpoint(), T0, Baseline.Source.MANUAL));
    baselines.set(
        Baseline.of("qwen-run2", zero.fingerprint(), zero.provenance().checkpoint().path(), 0, T0,
            Baseline.Source.MANUAL));
  }

  private static String key(String target, String probe, long step) {
    return target + "/" + probe + "/" + step;
  }

  static String fingerprint(String family, Run run, long step) {
    long salt =
        (family.equals("qwen") ? 1L : 2L) << 40 | (run == Run.RUN2 && step != 0 ? 1L << 32 : 0);
    return "sampled-" + String.format("%064x", salt | step);
  }

  static String probeHash(String family, ProbeKind kind) {
    return String.format("%064x", (family.equals("qwen") ? 100 : 200) + kind.ordinal());
  }

  private Measurement m(String family, Run run, ProbeKind kind, long step, List<PromptCounts> p) {
    return m(family, run, kind, step, p, "j-" + family + "-" + run.suffix() + "-" + kind.suffix()
        + "-" + step);
  }

  private Measurement m(
      String family, Run run, ProbeKind kind, long step, List<PromptCounts> p, String job) {
    return counts
        .probeHash(probeHash(family, kind))
        .measurement(
            family + "-" + run.suffix(),
            family + "-" + kind.suffix(),
            fingerprint(family, run, step),
            job,
            step,
            T0.plusSeconds(step),
            p);
  }

  private EvaluationData load() {
    return EvaluationData.load(results, baselines);
  }

  @Test
  public void loadsSeriesInStepOrder() {
    fullFamily(Set.of());
    EvaluationData data = load();
    assertEquals(List.of("qwen"), data.families());
    List<Long> run1 = steps(data.series("qwen", ProbeKind.CHAT, Run.RUN1));
    assertEquals(ExpectedSteps.RUN1, run1);
    assertEquals(0L, (long) run1.get(0));
    assertEquals(ExpectedSteps.RUN2, steps(data.series("qwen", ProbeKind.CODE_LIVE, Run.RUN2)));
    assertEquals(0, data.baseline("qwen", ProbeKind.CHAT).provenance().checkpoint().step());
    assertEquals("qwen-run1", data.baseline("qwen", ProbeKind.CHAT).provenance().targetName());
    assertTrue(data.series("qwen", ProbeKind.CHAT, Run.RUN2).at(625).isPresent());
    assertFalse(data.series("qwen", ProbeKind.CHAT, Run.RUN2).at(610).isPresent());
    assertTrue(data.missing().isEmpty());
  }

  private static List<Long> steps(Series s) {
    return s.inStepOrder().stream()
        .map(x -> x.provenance().checkpoint().step())
        .collect(Collectors.toList());
  }

  @Test
  public void expectedSteps() {
    assertEquals(13, ExpectedSteps.RUN1.size());
    assertEquals(1200L, (long) ExpectedSteps.RUN1.get(12));
    assertEquals(
        List.of(625L, 650L, 675L, 700L, 725L, 750L, 775L, 800L, 900L, 1000L, 1100L, 1200L),
        ExpectedSteps.RUN2);
  }

  @Test
  public void runTwoBaselineIsRunOnesCheckpointZero() {
    fullFamily(Set.of());
    Measurement other = results.all().get(5);
    baselines.set(
        Baseline.of("qwen-run2", other.fingerprint(), other.provenance().checkpoint().path(), 100,
            T0, Baseline.Source.MANUAL));
    DataException e = assertThrows(DataException.class, this::load);
    assertTrue(e.getMessage(), e.getMessage().contains("baselines.json"));
    assertTrue(e.getMessage(), e.getMessage().contains("qwen-run2"));
  }

  @Test
  public void missingBaselineFails() {
    fullFamily(Set.of());
    baselines = new InMemoryBaselineRepository();
    DataException e = assertThrows(DataException.class, this::load);
    assertTrue(e.getMessage(), e.getMessage().contains("baselines.json"));
  }

  @Test
  public void listsMissingCheckpoints() {
    fullFamily(Set.of(key("qwen-run2", "qwen-chat", 625)));
    EvaluationData data = load();
    assertEquals(1, data.missing().size());
    MissingMeasurement missing = data.missing().get(0);
    assertEquals("qwen-run2", missing.target());
    assertEquals("qwen-chat", missing.probeId());
    assertEquals(625, missing.step());
    assertFalse(data.series("qwen", ProbeKind.CHAT, Run.RUN2).at(625).isPresent());
  }

  @Test
  public void missingCheckpointZeroFails() {
    fullFamily(Set.of(key("qwen-run1", "qwen-code", 0)));
    DataException e = assertThrows(DataException.class, this::load);
    assertTrue(e.getMessage(), e.getMessage().contains("qwen-code"));
  }

  @Test
  public void failsOnUnexpectedStep() {
    fullFamily(Set.of());
    results.append(m("qwen", Run.RUN2, ProbeKind.CHAT, 610, PROMPTS));
    DataException e = assertThrows(DataException.class, this::load);
    assertTrue(e.getMessage(), e.getMessage().contains("610"));
  }

  @Test
  public void failsOnRunTwoCheckpointZero() {
    fullFamily(Set.of());
    results.append(m("qwen", Run.RUN2, ProbeKind.CHAT, 0, PROMPTS, "j-run2-zero"));
    DataException e = assertThrows(DataException.class, this::load);
    assertTrue(e.getMessage(), e.getMessage().contains("j-run2-zero"));
  }

  @Test
  public void failsOnDuplicateMeasurement() {
    fullFamily(Set.of());
    results.append(m("qwen", Run.RUN1, ProbeKind.CHAT, 300, PROMPTS, "j-again"));
    DataException e = assertThrows(DataException.class, this::load);
    assertTrue(e.getMessage(), e.getMessage().contains("j-again"));
    assertTrue(e.getMessage(), e.getMessage().contains("j-qwen-run1-chat-300"));
  }

  private void assertBadPerPrompt(List<PromptCounts> prompts) {
    assertBadPerPrompt(prompts, () -> {});
  }

  private void assertBadPerPrompt(List<PromptCounts> prompts, Runnable beforeBad) {
    fullFamily(Set.of(key("qwen-run1", "qwen-chat", 300)));
    beforeBad.run();
    results.append(m("qwen", Run.RUN1, ProbeKind.CHAT, 300, prompts, "j-bad"));
    DataException e = assertThrows(DataException.class, this::load);
    assertTrue(e.getMessage(), e.getMessage().contains("qwen-run1"));
    assertTrue(e.getMessage(), e.getMessage().contains("qwen-chat"));
    assertTrue(e.getMessage(), e.getMessage().contains("j-bad"));
  }

  @Test
  public void failsOnPerPromptOutOfOrder() {
    List<PromptCounts> p = new ArrayList<>(PROMPTS);
    PromptCounts first = p.remove(0);
    p.add(1, first);
    assertBadPerPrompt(p);
  }

  @Test
  public void failsOnPerPromptGap() {
    List<PromptCounts> p = new ArrayList<>(PROMPTS.subList(0, 3));
    p.add(PromptCounts.of(4, 10, 40, 33));
    assertBadPerPrompt(p);
  }

  @Test
  public void failsOnPerPromptSizeMismatch() {
    assertBadPerPrompt(PROMPTS, () -> counts.numPrompts(5));
  }

  @Test
  public void failsOnPromptCountDifferingWithinAProbe() {
    assertBadPerPrompt(PROMPTS.subList(0, 3));
  }

  @Test
  public void failsOnMoreThanOneSeed() {
    counts.seeds(2);
    fullFamily(Set.of());
    DataException e = assertThrows(DataException.class, this::load);
    assertTrue(e.getMessage(), e.getMessage().contains("expected exactly one seed"));
  }

  @Test
  public void failsOnExcludedPrompts() {
    counts.excludedPrompts(1);
    fullFamily(Set.of());
    DataException e = assertThrows(DataException.class, this::load);
    assertTrue(e.getMessage(), e.getMessage().contains("excluded_prompts"));
  }

  @Test
  public void failsOnProbeOfAnotherFamily() {
    fullFamily(Set.of());
    results.append(
        counts.probeHash(probeHash("smol", ProbeKind.CHAT)).measurement("qwen-run1", "smol-chat",
            fingerprint("qwen", Run.RUN1, 300), "j-cross", 300, T0, PROMPTS));
    DataException e = assertThrows(DataException.class, this::load);
    assertTrue(e.getMessage(), e.getMessage().contains("j-cross"));
  }

  @Test
  public void checkpointZeroMustBeTheSameForEveryProbe() {
    fullFamily(Set.of(key("qwen-run1", "qwen-code-live", 0)));
    results.append(
        counts.probeHash(probeHash("qwen", ProbeKind.CODE_LIVE)).measurement("qwen-run1",
            "qwen-code-live", "sampled-" + "9".repeat(64), "j-other-zero", 0, T0, PROMPTS));
    DataException e = assertThrows(DataException.class, this::load);
    assertTrue(e.getMessage(), e.getMessage().contains("j-other-zero"));
  }

  @Test
  public void familiesAreInNameOrderWhateverTheInsertionOrder() {
    ScoringFixtures.family(results, baselines, "smol", 4, (r, k, s) -> 0, Set.of());
    ScoringFixtures.family(results, baselines, "qwen", 4, (r, k, s) -> 0, Set.of());
    // A List, so iteration order is what is compared.
    assertEquals(List.of("qwen", "smol"), load().families());
  }
}
