package dev.draftwatch.trigger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.draftwatch.config.TriggerSpec;
import dev.draftwatch.domain.Checkpoint;
import dev.draftwatch.domain.CheckpointType;
import dev.draftwatch.domain.Decoding;
import dev.draftwatch.domain.Draft;
import dev.draftwatch.domain.DraftStructure;
import dev.draftwatch.domain.Estimator;
import dev.draftwatch.domain.Probe;
import dev.draftwatch.domain.PromptSet;
import dev.draftwatch.domain.ResolvedProbe;
import dev.draftwatch.exec.FailureReason;
import dev.draftwatch.exec.Job;
import dev.draftwatch.exec.JobHandle;
import dev.draftwatch.exec.MeasurementSpec;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.Test;

public class TriggerChainTest {
  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
  private static final Path PROMPTS = Paths.get("/p.jsonl");
  private static final ResolvedProbe PROBE =
      ResolvedProbe.of(
          Probe.of(
              "chat",
              Draft.of("d", Paths.get("/d"), DraftStructure.CHAIN),
              PROMPTS,
              Decoding.of(BigDecimal.ZERO, 8, 3, "bfloat16"),
              Estimator.TOKEN_WEIGHTED,
              List.of(0)),
          "sampled-d",
          PromptSet.of(PROMPTS, "a".repeat(64), 3),
          "b".repeat(64));

  /** A history scripted by the test. */
  private static final class FakeHistory implements History {
    final Set<String> results = new HashSet<>();
    final List<Job> jobs = new ArrayList<>();
    int active;

    @Override
    public boolean hasResult(String fingerprint, String probeHash) {
      return results.contains(fingerprint + "/" + probeHash);
    }

    @Override
    public List<Job> jobs(String fingerprint, String probeHash) {
      List<Job> out = new ArrayList<>();
      for (Job j : jobs) {
        if (j.spec().checkpoint().fingerprint().equals(fingerprint)) {
          out.add(j);
        }
      }
      return out;
    }

    @Override
    public int activeJobs(String target) {
      return active;
    }
  }

  private final FakeHistory history = new FakeHistory();

  private static Checkpoint checkpoint(long step, boolean isFinal) {
    return Checkpoint.builder()
        .targetName("run")
        .path(Paths.get("/runs/checkpoint-" + step))
        .step(step)
        .fingerprint("sampled-" + step)
        .type(CheckpointType.FULL)
        .isFinal(isFinal)
        .build();
  }

  private static Job job(Checkpoint c) {
    MeasurementSpec spec =
        MeasurementSpec.of(
            c, PROBE, List.of("h"), Paths.get("/w"), Paths.get("/s/raw/j1"), "local");
    return Job.created("j1", spec, T0);
  }

  private static TriggerChain defaultChain() {
    List<TriggerRule> rules = new ArrayList<>();
    for (TriggerSpec spec : TriggerSpec.defaultChain()) {
      switch (spec.kind()) {
        case NOT_ALREADY_MEASURED:
          rules.add(new NotAlreadyMeasuredRule());
          break;
        case MAX_PENDING:
          rules.add(new MaxPendingRule(spec.argument().getAsInt()));
          break;
        case ALWAYS_FINAL:
          rules.add(new AlwaysFinalRule());
          break;
        case EVERY_N_STEPS:
          rules.add(new EveryNStepsRule(spec.argument().getAsInt()));
          break;
        default:
          throw new IllegalStateException();
      }
    }
    return new TriggerChain(rules);
  }

  // --- rules ------------------------------------------------------------------------------------

  @Test
  public void notAlreadyMeasuredRejectsAResultOrAnyJob() {
    NotAlreadyMeasuredRule rule = new NotAlreadyMeasuredRule();
    Checkpoint c = checkpoint(100, false);
    assertEquals(TriggerDecision.abstain(), rule.evaluate(c, PROBE, history));
    history.results.add("sampled-100/" + PROBE.hash());
    assertEquals(TriggerDecision.reject("already measured"), rule.evaluate(c, PROBE, history));
    history.results.clear();
    Job failed =
        job(c)
            .submitted(JobHandle.of("local", "1", Paths.get("/r"), T0, Optional.empty()), T0)
            .failed(FailureReason.OUT_OF_MEMORY, "exit 4", T0);
    history.jobs.add(failed);
    TriggerDecision d = rule.evaluate(c, PROBE, history);
    assertEquals(TriggerDecision.Kind.REJECT, d.kind());
    assertTrue(d.reason().get(), d.reason().get().startsWith("job j1 is FAILED (out_of_memory)"));
  }

  @Test
  public void maxPendingRejectsAtTheLimit() {
    MaxPendingRule rule = new MaxPendingRule(2);
    history.active = 1;
    TriggerDecision below = rule.evaluate(checkpoint(1, false), PROBE, history);
    assertEquals(TriggerDecision.Kind.ABSTAIN, below.kind());
    history.active = 2;
    assertEquals(
        TriggerDecision.reject("2 jobs of this target are active (limit 2)"),
        rule.evaluate(checkpoint(1, false), PROBE, history));
  }

  @Test
  public void alwaysFinalAcceptsOnlyFinalCheckpoints() {
    AlwaysFinalRule rule = new AlwaysFinalRule();
    assertEquals(
        TriggerDecision.accept("final checkpoint"),
        rule.evaluate(checkpoint(7, true), PROBE, history));
    assertEquals(TriggerDecision.abstain(), rule.evaluate(checkpoint(7, false), PROBE, history));
  }

  @Test
  public void everyNStepsAbstainsOnMultiplesAndRejectsTheRest() {
    EveryNStepsRule rule = new EveryNStepsRule(500);
    assertEquals(TriggerDecision.abstain(), rule.evaluate(checkpoint(1000, false), PROBE, history));
    assertEquals(TriggerDecision.abstain(), rule.evaluate(checkpoint(0, false), PROBE, history));
    assertEquals(
        TriggerDecision.reject("step 750 is not a multiple of 500"),
        rule.evaluate(checkpoint(750, false), PROBE, history));
  }

  // --- chain ------------------------------------------------------------------------------------

  @Test
  public void firstNonAbstainingRuleDecides() {
    TriggerChain chain =
        new TriggerChain(
            List.of(
                new NotAlreadyMeasuredRule(), new AlwaysFinalRule(), new EveryNStepsRule(500)));
    TriggerChain.Outcome finalOffStep = chain.decide(checkpoint(750, true), PROBE, history);
    assertTrue(finalOffStep.accepted());
    assertEquals(Optional.of("always_final"), finalOffStep.rule());
    TriggerChain.Outcome offStep = chain.decide(checkpoint(750, false), PROBE, history);
    assertFalse(offStep.accepted());
    assertEquals(Optional.of("every_n_steps(500)"), offStep.rule());
  }

  @Test
  public void everyRuleAbstainingAccepts() {
    TriggerChain chain = new TriggerChain(List.of(new EveryNStepsRule(1)));
    TriggerChain.Outcome o = chain.decide(checkpoint(3, false), PROBE, history);
    assertEquals("accepted: every rule abstained", o.toString());
  }

  @Test
  public void measuredFinalCheckpointIsNotMeasuredAgainUnderTheDefaultChain() {
    history.results.add("sampled-900/" + PROBE.hash());
    TriggerChain.Outcome o = defaultChain().decide(checkpoint(900, true), PROBE, history);
    assertFalse(o.accepted());
    assertEquals(Optional.of("not_already_measured"), o.rule());
  }

  @Test
  public void defaultChainMeasuresEveryNewCheckpointUntilFourAreActive() {
    TriggerChain chain = defaultChain();
    assertTrue(chain.decide(checkpoint(123, false), PROBE, history).accepted());
    history.active = 4;
    TriggerChain.Outcome o = chain.decide(checkpoint(124, false), PROBE, history);
    assertEquals(Optional.of("max_pending(4)"), o.rule());
  }
}
