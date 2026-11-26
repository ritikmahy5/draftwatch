package dev.draftwatch.scoring;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * The metrics of docs/EVALUATION.md's table for one family, reference probe, configuration, and
 * probe size, from the ground-truth rows and both runs' flags. AMBIGUOUS checkpoints are
 * excluded from every pooled rate. B0 tests for any drop, so its false-alarm rate is also given
 * without the NOT_REGRESSED checkpoints whose reference interval lies below 0 ("small real
 * drops"), which are listed.
 */
public final class DetectionMetrics {
  private static final long CONTROL_FIRST = 100;
  private static final long CONTROL_LAST = 600;

  private final List<CheckpointRow> rows;
  private final FlagMatrix run1;
  private final FlagMatrix run2;
  private final int subsets;

  /**
   * @throws IllegalArgumentException unless each run's rows are exactly its matrix's steps, in
   *     order, and both matrices have the same number of subsets
   */
  public DetectionMetrics(List<CheckpointRow> rows, FlagMatrix run1, FlagMatrix run2) {
    this.rows = List.copyOf(rows);
    this.run1 = run1;
    this.run2 = run2;
    if (run1.subsets() != run2.subsets()) {
      throw new IllegalArgumentException(
          "run 1 has " + run1.subsets() + " subsets, run 2 has " + run2.subsets());
    }
    this.subsets = run1.subsets();
    requireAligned(Run.RUN1, run1);
    requireAligned(Run.RUN2, run2);
  }

  private void requireAligned(Run run, FlagMatrix m) {
    List<Long> steps = new ArrayList<>();
    for (CheckpointRow r : rows) {
      if (r.run() == run) {
        steps.add(r.step());
      }
    }
    if (!steps.equals(m.steps())) {
      throw new IllegalArgumentException(
          run.suffix() + " rows are at steps " + steps + ", flags at " + m.steps());
    }
  }

  private FlagMatrix matrix(CheckpointRow r) {
    return r.run() == Run.RUN1 ? run1 : run2;
  }

  private int column(CheckpointRow r) {
    return matrix(r).steps().indexOf(r.step());
  }

  private int flagsOn(CheckpointRow r) {
    FlagMatrix m = matrix(r);
    int c = column(r);
    int n = 0;
    for (int s = 0; s < subsets; s++) {
      if (m.flagged(s, c)) {
        n++;
      }
    }
    return n;
  }

  /** For each row, in order: the fraction of subsets that flag it. */
  public List<Rate> alarmRates() {
    List<Rate> out = new ArrayList<>();
    for (CheckpointRow r : rows) {
      out.add(Rate.of(flagsOn(r), subsets, "no subsets"));
    }
    return out;
  }

  private Rate pooled(List<CheckpointRow> over, String whyIfEmpty) {
    int flagged = 0;
    for (CheckpointRow r : over) {
      flagged += flagsOn(r);
    }
    return Rate.of(flagged, over.size() * subsets, whyIfEmpty);
  }

  private List<CheckpointRow> labeled(Label label) {
    List<CheckpointRow> out = new ArrayList<>();
    for (CheckpointRow r : rows) {
      if (r.label() == label) {
        out.add(r);
      }
    }
    return out;
  }

  /** Flags over NOT_REGRESSED (subset, checkpoint) cells of both runs. */
  public Rate falseAlarmRate() {
    return pooled(labeled(Label.NOT_REGRESSED), "no NOT_REGRESSED checkpoints");
  }

  /** As {@link #falseAlarmRate}, without the {@link #realSmallDrops}. */
  public Rate falseAlarmRateExcludingRealDrops() {
    List<CheckpointRow> over = new ArrayList<>(labeled(Label.NOT_REGRESSED));
    over.removeAll(realSmallDrops());
    return pooled(over, "no NOT_REGRESSED checkpoints with U >= 0");
  }

  /** NOT_REGRESSED checkpoints whose reference interval has U < 0. */
  public List<CheckpointRow> realSmallDrops() {
    List<CheckpointRow> out = new ArrayList<>();
    for (CheckpointRow r : labeled(Label.NOT_REGRESSED)) {
      if (r.reference().upper() < 0) {
        out.add(r);
      }
    }
    return out;
  }

  /** Flags over REGRESSED cells of both runs. */
  public Rate detectionRate() {
    return pooled(labeled(Label.REGRESSED), "no REGRESSED checkpoints");
  }

  /** Subsets that flag at least one NOT_REGRESSED checkpoint of run 1. */
  public Rate runFalseAlarmRate() {
    List<CheckpointRow> over = new ArrayList<>();
    for (CheckpointRow r : labeled(Label.NOT_REGRESSED)) {
      if (r.run() == Run.RUN1) {
        over.add(r);
      }
    }
    return subsetsFlaggingAny(over, "no NOT_REGRESSED checkpoints in run 1");
  }

  /** Subsets that flag any run-1 checkpoint, whatever its label. */
  public Rate anyRun1AlarmRate() {
    List<CheckpointRow> over = new ArrayList<>();
    for (CheckpointRow r : rows) {
      if (r.run() == Run.RUN1) {
        over.add(r);
      }
    }
    return subsetsFlaggingAny(over, "no run-1 checkpoints");
  }

  private Rate subsetsFlaggingAny(List<CheckpointRow> over, String whyIfEmpty) {
    if (over.isEmpty()) {
      return Rate.of(0, 0, whyIfEmpty);
    }
    int n = 0;
    for (int s = 0; s < subsets; s++) {
      for (CheckpointRow r : over) {
        if (matrix(r).flagged(s, column(r))) {
          n++;
          break;
        }
      }
    }
    return Rate.of(n, subsets, "no subsets");
  }

  /** Detection delay on run 2; see {@link Delay}. */
  public Delay delay() {
    List<Long> controlFailed = new ArrayList<>();
    for (CheckpointRow r : labeled(Label.REGRESSED)) {
      if (r.run() == Run.RUN1 && r.step() >= CONTROL_FIRST && r.step() <= CONTROL_LAST) {
        controlFailed.add(r.step());
      }
    }
    List<CheckpointRow> branch = new ArrayList<>();
    for (CheckpointRow r : rows) {
      if (r.run() == Run.RUN2) {
        branch.add(r);
      }
    }
    OptionalInt onsetIndex = OptionalInt.empty();
    for (int i = 0; i < branch.size(); i++) {
      if (branch.get(i).label() == Label.REGRESSED) {
        onsetIndex = OptionalInt.of(i);
        break;
      }
    }
    OptionalLong onset =
        onsetIndex.isPresent()
            ? OptionalLong.of(branch.get(onsetIndex.getAsInt()).step())
            : OptionalLong.empty();
    if (!controlFailed.isEmpty() || onsetIndex.isEmpty()) {
      return new Delay(
          controlFailed.isEmpty()
              ? Delay.Status.NO_REGRESSED_BRANCH_CHECKPOINT
              : Delay.Status.CONTROL_FAILED,
          onset,
          OptionalLong.empty(),
          OptionalDouble.empty(),
          OptionalDouble.empty(),
          OptionalDouble.empty(),
          controlFailed);
    }
    int first = onsetIndex.getAsInt();
    long onsetStep = onset.getAsLong();
    long[] delays = new long[subsets];
    int misses = 0;
    int early = 0;
    int falseAlarms = 0;
    for (int s = 0; s < subsets; s++) {
      delays[s] = Long.MAX_VALUE;
      for (int i = first; i < branch.size(); i++) {
        if (run2.flagged(s, i)) {
          delays[s] = branch.get(i).step() - onsetStep;
          break;
        }
      }
      if (delays[s] == Long.MAX_VALUE) {
        misses++;
      }
      boolean flaggedAmbiguous = false;
      boolean flaggedNotRegressed = false;
      for (int i = 0; i < first; i++) {
        if (run2.flagged(s, i)) {
          flaggedAmbiguous |= branch.get(i).label() == Label.AMBIGUOUS;
          flaggedNotRegressed |= branch.get(i).label() == Label.NOT_REGRESSED;
        }
      }
      early += flaggedAmbiguous ? 1 : 0;
      falseAlarms += flaggedNotRegressed ? 1 : 0;
    }
    if (subsets == 0) {
      throw new IllegalStateException("no subsets to score the delay on");
    }
    long[] sorted = delays.clone();
    Arrays.sort(sorted);
    long median = sorted[(subsets - 1) / 2];
    return new Delay(
        Delay.Status.SCORED,
        onset,
        median == Long.MAX_VALUE ? OptionalLong.empty() : OptionalLong.of(median),
        OptionalDouble.of((double) misses / subsets),
        OptionalDouble.of((double) early / subsets),
        OptionalDouble.of((double) falseAlarms / subsets),
        controlFailed);
  }

  /**
   * For paired-bootstrap flags: the fraction of (subset, checkpoint) intervals with L ≤ Δ_ref ≤
   * U, over every compared checkpoint whatever its label.
   */
  public Rate coverage() {
    int contained = 0;
    int total = 0;
    for (CheckpointRow r : rows) {
      FlagMatrix m = matrix(r);
      int c = column(r);
      double delta = r.reference().delta();
      for (int s = 0; s < subsets; s++) {
        OptionalDouble lo = m.lower(s, c);
        OptionalDouble hi = m.upper(s, c);
        if (lo.isEmpty() || hi.isEmpty()) {
          return Rate.of(0, 0, "no intervals: not a paired-bootstrap configuration");
        }
        total++;
        if (lo.getAsDouble() <= delta && delta <= hi.getAsDouble()) {
          contained++;
        }
      }
    }
    return Rate.of(contained, total, "no compared checkpoints");
  }
}
