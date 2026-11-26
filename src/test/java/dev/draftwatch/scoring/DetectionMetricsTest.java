package dev.draftwatch.scoring;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.draftwatch.detect.DetectorVerdict;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import org.junit.Test;

/** The metrics of docs/EVALUATION.md's table, on hand-built flags and invented intervals. */
public class DetectionMetricsTest {
  private static final double[] NOT_REGRESSED = {0.0, -0.01, 0.01};
  private static final double[] SMALL_REAL_DROP = {-0.005, -0.015, -0.001};
  private static final double[] AMBIGUOUS = {-0.015, -0.03, 0.0};
  private static final double[] REGRESSED = {-0.03, -0.04, -0.02};

  private static CheckpointRow row(Run run, long step, double[] ref) {
    return CheckpointRow.of(run, step, ReferenceDifference.of(ref[0], ref[1], ref[2]));
  }

  /** Flags as strings, one per subset: 'T' flagged, 'F' not, 'I' insufficient data. */
  private static FlagMatrix flags(List<Long> steps, String... subsets) {
    DetectorVerdict.Kind[][] kinds = new DetectorVerdict.Kind[subsets.length][steps.size()];
    double[][] nan = new double[subsets.length][steps.size()];
    for (int s = 0; s < subsets.length; s++) {
      Arrays.fill(nan[s], Double.NaN);
      for (int i = 0; i < steps.size(); i++) {
        char c = subsets[s].charAt(i);
        kinds[s][i] =
            c == 'T'
                ? DetectorVerdict.Kind.REGRESSION
                : c == 'I' ? DetectorVerdict.Kind.INSUFFICIENT_DATA : DetectorVerdict.Kind.OK;
      }
    }
    return new FlagMatrix(steps, kinds, nan, nan);
  }

  private static final List<Long> RUN1_STEPS = List.of(100L, 200L, 300L);
  private static final List<Long> RUN2_STEPS = List.of(650L, 700L, 800L, 825L, 900L);

  private static List<CheckpointRow> rows() {
    return List.of(
        row(Run.RUN1, 100, NOT_REGRESSED),
        row(Run.RUN1, 200, SMALL_REAL_DROP),
        row(Run.RUN1, 300, AMBIGUOUS),
        row(Run.RUN2, 650, NOT_REGRESSED),
        row(Run.RUN2, 700, AMBIGUOUS),
        row(Run.RUN2, 800, REGRESSED),
        row(Run.RUN2, 825, REGRESSED),
        row(Run.RUN2, 900, REGRESSED));
  }

  private static DetectionMetrics metrics() {
    return new DetectionMetrics(
        rows(),
        flags(RUN1_STEPS, "TTF", "FTT", "FFT", "FTF"),
        flags(RUN2_STEPS, "FFTFF", "FTFTF", "TFFFT", "FFFFF"));
  }

  private static void assertRate(double expected, int flagged, int total, Rate r) {
    assertEquals(expected, r.value().getAsDouble(), 0.0);
    assertEquals(flagged, r.flagged());
    assertEquals(total, r.total());
    assertFalse(r.undefinedReason().isPresent());
  }

  @Test
  public void alarmRatePerCheckpoint() {
    List<Rate> rates = metrics().alarmRates();
    assertEquals(8, rates.size());
    assertRate(0.25, 1, 4, rates.get(0));
    assertRate(0.75, 3, 4, rates.get(1));
    assertRate(0.5, 2, 4, rates.get(2));
    assertRate(0.25, 1, 4, rates.get(4));
    assertRate(0.25, 1, 4, rates.get(7));
  }

  @Test
  public void pooledRates() {
    DetectionMetrics m = metrics();
    // NOT_REGRESSED cells: run 1 steps 100 and 200, run 2 step 650; AMBIGUOUS is excluded.
    assertRate(5.0 / 12, 5, 12, m.falseAlarmRate());
    // REGRESSED cells: run 2 steps 800, 825, 900.
    assertRate(3.0 / 12, 3, 12, m.detectionRate());
  }

  @Test
  public void realSmallDropsSplit() {
    DetectionMetrics m = metrics();
    assertEquals(1, m.realSmallDrops().size());
    assertEquals(200, m.realSmallDrops().get(0).step());
    assertEquals(Run.RUN1, m.realSmallDrops().get(0).run());
    // Without step 200 (U < 0): steps 100 and 650.
    assertRate(2.0 / 8, 2, 8, m.falseAlarmRateExcludingRealDrops());
  }

  @Test
  public void runFalseAlarmCountsSubsetsNotCells() {
    DetectionMetrics m = metrics();
    // Subset 0 flags two NOT_REGRESSED checkpoints of run 1 but counts once.
    assertRate(0.75, 3, 4, m.runFalseAlarmRate());
    assertRate(1.0, 4, 4, m.anyRun1AlarmRate());
  }

  @Test
  public void delayMedianAndMisses() {
    Delay d = metrics().delay();
    assertEquals(Delay.Status.SCORED, d.status());
    assertEquals(OptionalLong.of(800), d.onsetStep());
    // Delays {0, 25, 100, infinite}: the lower middle is 25.
    assertEquals(OptionalLong.of(25), d.medianSteps());
    assertEquals(OptionalDouble.of(0.25), d.missFraction());
    assertTrue(d.controlFailedSteps().isEmpty());
  }

  @Test
  public void earlyAndFalseAlarmBeforeOnset() {
    Delay d = metrics().delay();
    assertEquals(OptionalDouble.of(0.25), d.earlyFraction()); // subset 1 at 700
    assertEquals(OptionalDouble.of(0.25), d.falseAlarmBeforeOnsetFraction()); // subset 2 at 650
  }

  @Test
  public void delayMedianInfinite() {
    Delay d =
        new DetectionMetrics(
                rows(),
                flags(RUN1_STEPS, "FFF", "FFF", "FFF", "FFF"),
                flags(RUN2_STEPS, "FFTFF", "FFFFF", "TFFFF", "IIFFF"))
            .delay();
    assertEquals(Delay.Status.SCORED, d.status());
    assertFalse(d.medianSteps().isPresent());
    assertEquals(OptionalDouble.of(0.75), d.missFraction());
  }

  @Test
  public void controlFailed() {
    List<CheckpointRow> rows = new ArrayList<>(rows());
    rows.set(1, row(Run.RUN1, 200, REGRESSED));
    Delay d =
        new DetectionMetrics(
                rows,
                flags(RUN1_STEPS, "FFF", "FFF", "FFF", "FFF"),
                flags(RUN2_STEPS, "FFTFF", "FFFFF", "FFFFF", "FFFFF"))
            .delay();
    assertEquals(Delay.Status.CONTROL_FAILED, d.status());
    assertEquals(List.of(200L), d.controlFailedSteps());
    assertFalse(d.medianSteps().isPresent());
    assertFalse(d.missFraction().isPresent());
    assertFalse(d.earlyFraction().isPresent());
  }

  @Test
  public void trunkRegressionAfter600DoesNotFailTheControl() {
    List<Long> steps = List.of(100L, 900L);
    Delay d =
        new DetectionMetrics(
                List.of(
                    row(Run.RUN1, 100, NOT_REGRESSED),
                    row(Run.RUN1, 900, REGRESSED),
                    row(Run.RUN2, 800, REGRESSED)),
                flags(steps, "FF"),
                flags(List.of(800L), "T"))
            .delay();
    assertEquals(Delay.Status.SCORED, d.status());
    assertEquals(OptionalLong.of(0), d.medianSteps());
  }

  @Test
  public void noRegressedBranch() {
    List<CheckpointRow> rows = new ArrayList<>(rows());
    for (int i = 5; i < 8; i++) {
      rows.set(i, row(Run.RUN2, RUN2_STEPS.get(i - 3), AMBIGUOUS));
    }
    Delay d =
        new DetectionMetrics(
                rows,
                flags(RUN1_STEPS, "FFF", "FFF", "FFF", "FFF"),
                flags(RUN2_STEPS, "FFTFF", "FFFFF", "FFFFF", "FFFFF"))
            .delay();
    assertEquals(Delay.Status.NO_REGRESSED_BRANCH_CHECKPOINT, d.status());
    assertFalse(d.onsetStep().isPresent());
    assertFalse(d.medianSteps().isPresent());
  }

  @Test
  public void coverageCountsInclusiveBounds() {
    List<Long> steps = List.of(100L, 200L);
    DetectorVerdict.Kind[][] kinds = {
      {DetectorVerdict.Kind.OK, DetectorVerdict.Kind.OK},
      {DetectorVerdict.Kind.OK, DetectorVerdict.Kind.REGRESSION}
    };
    // Δ_ref: 0.0 at 100, -0.005 at 200 (SMALL_REAL_DROP).
    double[][] lower = {{0.0, -0.02}, {-0.01, -0.004}};
    double[][] upper = {{0.01, -0.005}, {-0.001, 0.0}};
    FlagMatrix b0 = new FlagMatrix(steps, kinds, lower, upper);
    DetectionMetrics m =
        new DetectionMetrics(
            List.of(row(Run.RUN1, 100, NOT_REGRESSED), row(Run.RUN1, 200, SMALL_REAL_DROP)),
            b0,
            flags(List.of(), "", ""));
    // Contained: [0, 0.01] ∋ 0 (lower bound equal), [-0.02, -0.005] ∋ -0.005 (upper equal).
    // Not: [-0.01, -0.001] ∌ 0, [-0.004, 0] ∌ -0.005.
    assertRate(0.5, 2, 4, m.coverage());
    List<Rate> byCheckpoint = m.coverageByCheckpoint();
    assertRate(0.5, 1, 2, byCheckpoint.get(0));
    assertRate(0.5, 1, 2, byCheckpoint.get(1));
  }

  @Test
  public void coverageWithoutIntervalsIsUndefined() {
    assertTrue(metrics().coverageByCheckpoint().isEmpty());
    Rate c = metrics().coverage();
    assertFalse(c.value().isPresent());
    assertTrue(c.undefinedReason().isPresent());
  }

  @Test
  public void emptyDenominatorIsUndefined() {
    DetectionMetrics m =
        new DetectionMetrics(
            List.of(row(Run.RUN1, 100, AMBIGUOUS), row(Run.RUN2, 800, REGRESSED)),
            flags(List.of(100L), "T"),
            flags(List.of(800L), "T"));
    Rate fa = m.falseAlarmRate();
    assertFalse(fa.value().isPresent());
    assertEquals("no NOT_REGRESSED checkpoints", fa.undefinedReason().get());
    assertEquals(0, fa.total());
    assertFalse(m.runFalseAlarmRate().value().isPresent());
    assertTrue(m.runFalseAlarmRate().undefinedReason().isPresent());
    assertFalse(m.falseAlarmRateExcludingRealDrops().value().isPresent());
    assertRate(1.0, 1, 1, m.anyRun1AlarmRate());
  }

  @Test(expected = IllegalArgumentException.class)
  public void rowsMustMatchTheFlagColumns() {
    new DetectionMetrics(
        List.of(row(Run.RUN1, 100, AMBIGUOUS)),
        flags(List.of(100L, 200L), "TT"),
        flags(List.of(), ""));
  }

  @Test(expected = IllegalArgumentException.class)
  public void runsMustHaveTheSameSubsets() {
    new DetectionMetrics(
        List.of(row(Run.RUN1, 100, AMBIGUOUS)), flags(List.of(100L), "T"), flags(List.of()));
  }
}
