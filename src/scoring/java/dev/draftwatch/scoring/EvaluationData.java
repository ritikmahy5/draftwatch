package dev.draftwatch.scoring;

import dev.draftwatch.domain.Baseline;
import dev.draftwatch.domain.Measurement;
import dev.draftwatch.domain.PromptCounts;
import dev.draftwatch.domain.SeedReport;
import dev.draftwatch.store.BaselineRepository;
import dev.draftwatch.store.ResultRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The evaluation's stored results, validated for scoring. Subset index i must mean the same
 * prompt at every checkpoint, so every result must hold exactly one seed whose per-prompt counts
 * are indexed 0 … num_prompts−1 in order, with no excluded prompts, and one probe's results must
 * all have the same number of prompts. There must be at most one result per target, probe, and
 * checkpoint, only at the runs' expected steps, and both runs of a family must be pinned to run
 * 1's checkpoint 0.
 */
public final class EvaluationData {
  private final Set<String> families;
  private final Map<String, Series> series;
  private final Map<String, Measurement> baselines;
  private final List<MissingMeasurement> missing;

  private EvaluationData(
      Set<String> families,
      Map<String, Series> series,
      Map<String, Measurement> baselines,
      List<MissingMeasurement> missing) {
    this.families = Set.copyOf(families);
    this.series = Map.copyOf(series);
    this.baselines = Map.copyOf(baselines);
    this.missing = List.copyOf(missing);
  }

  /**
   * Loads and validates every stored result.
   *
   * @throws DataException naming the target, probe, and job (or {@code baselines.json}) of the
   *     first violation
   */
  public static EvaluationData load(ResultRepository results, BaselineRepository baselineRepo) {
    Map<String, Measurement> byCheckpoint = new HashMap<>();
    Map<String, List<Measurement>> bySeries = new HashMap<>();
    Map<String, Measurement> promptCountWitness = new HashMap<>();
    Set<String> families = new TreeSet<>();
    for (Measurement m : results.all()) {
      String target = m.provenance().targetName();
      String probe = m.provenance().probeId();
      String where = target + " " + probe + " job " + m.jobId();
      TargetName t;
      ProbeName p;
      try {
        t = EvaluationNames.target(target);
        p = EvaluationNames.probe(probe);
      } catch (IllegalArgumentException e) {
        throw new DataException(where + ": " + e.getMessage(), e);
      }
      if (!t.family().equals(p.family())) {
        throw new DataException(where + ": the probe belongs to another family");
      }
      validateCounts(m, where);
      long step = m.provenance().checkpoint().step();
      if (!ExpectedSteps.of(t.run()).contains(step)) {
        throw new DataException(where + ": step " + step + " is not an expected checkpoint of "
            + t.run().suffix());
      }
      String checkpointKey = target + "/" + probe + "/" + step;
      Measurement earlier = byCheckpoint.putIfAbsent(checkpointKey, m);
      if (earlier != null) {
        throw new DataException(target + " " + probe + " step " + step
            + " has two results, jobs " + earlier.jobId() + " and " + m.jobId());
      }
      Measurement witness = promptCountWitness.putIfAbsent(probe, m);
      if (witness != null && witness.report().numPrompts() != m.report().numPrompts()) {
        throw new DataException(where + ": " + m.report().numPrompts() + " prompts, but job "
            + witness.jobId() + " of the same probe has " + witness.report().numPrompts());
      }
      families.add(t.family());
      bySeries.computeIfAbsent(key(t.family(), p.kind(), t.run()), k -> new ArrayList<>()).add(m);
    }
    Map<String, Series> series = new HashMap<>();
    Map<String, Measurement> baselines = new HashMap<>();
    List<MissingMeasurement> missing = new ArrayList<>();
    for (String family : families) {
      for (ProbeKind kind : ProbeKind.values()) {
        for (Run run : Run.values()) {
          String k = key(family, kind, run);
          Series s = new Series(bySeries.getOrDefault(k, List.of()));
          series.put(k, s);
          for (long step : ExpectedSteps.of(run)) {
            if (s.at(step).isEmpty() && step != 0) {
              missing.add(new MissingMeasurement(
                  family + "-" + run.suffix(), family + "-" + kind.suffix(), step));
            }
          }
        }
        Optional<Measurement> zero = series.get(key(family, kind, Run.RUN1)).at(0);
        if (zero.isEmpty()) {
          throw new DataException(family + "-run1 " + family + "-" + kind.suffix()
              + ": no result at checkpoint 0, the baseline");
        }
        baselines.put(family + "/" + kind, zero.get());
      }
      String fingerprint = baselines.get(family + "/" + ProbeKind.CHAT).fingerprint();
      for (ProbeKind kind : ProbeKind.values()) {
        Measurement zero = baselines.get(family + "/" + kind);
        if (!zero.fingerprint().equals(fingerprint)) {
          throw new DataException(family + "-run1 " + zero.provenance().probeId() + " job "
              + zero.jobId() + ": checkpoint 0 has fingerprint " + zero.fingerprint()
              + ", but the chat probe's checkpoint 0 has " + fingerprint);
        }
      }
      for (Run run : Run.values()) {
        requireBaseline(baselineRepo, family + "-" + run.suffix(), fingerprint);
      }
    }
    return new EvaluationData(families, series, baselines, missing);
  }

  private static void validateCounts(Measurement m, String where) {
    List<SeedReport> seeds = m.report().seeds();
    if (seeds.size() != 1) {
      throw new DataException(where + ": expected exactly one seed, found " + seeds.size());
    }
    SeedReport seed = seeds.get(0);
    if (seed.excludedPrompts() != 0) {
      throw new DataException(where + ": excluded_prompts is " + seed.excludedPrompts()
          + "; subset totals would differ from what the harness reports");
    }
    List<PromptCounts> prompts = seed.perPrompt();
    int n = m.report().numPrompts();
    if (prompts.size() != n) {
      throw new DataException(where + ": " + prompts.size() + " per_prompt entries, num_prompts is "
          + n);
    }
    for (int i = 0; i < n; i++) {
      if (prompts.get(i).promptIndex() != i) {
        throw new DataException(where + ": per_prompt[" + i + "] has prompt_index "
            + prompts.get(i).promptIndex() + "; expected " + i);
      }
    }
  }

  private static void requireBaseline(BaselineRepository repo, String target, String fingerprint) {
    Optional<Baseline> pinned = repo.get(target);
    if (pinned.isEmpty()) {
      throw new DataException("baselines.json: no baseline pinned for " + target);
    }
    if (!pinned.get().fingerprint().equals(fingerprint)) {
      throw new DataException("baselines.json: " + target + " is pinned to "
          + pinned.get().fingerprint() + " at step " + pinned.get().step()
          + ", not run 1's checkpoint 0 (" + fingerprint + ")");
    }
  }

  private static String key(String family, ProbeKind kind, Run run) {
    return family + "/" + kind + "/" + run;
  }

  /** The families with results, such as {@code qwen} and {@code smol}. */
  public Set<String> families() {
    return families;
  }

  /** One probe's results on one run; run 1's includes checkpoint 0. */
  public Series series(String family, ProbeKind kind, Run run) {
    Series s = series.get(key(family, kind, run));
    if (s == null) {
      throw new IllegalArgumentException("unknown family: " + family);
    }
    return s;
  }

  /** Run 1's checkpoint-0 result of the probe, the baseline of both runs. */
  public Measurement baseline(String family, ProbeKind kind) {
    Measurement m = baselines.get(family + "/" + kind);
    if (m == null) {
      throw new IllegalArgumentException("unknown family: " + family);
    }
    return m;
  }

  /** Expected checkpoints with no result, by target, probe, and step. */
  public List<MissingMeasurement> missing() {
    return missing;
  }
}
