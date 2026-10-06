package dev.draftwatch.report;

import dev.draftwatch.domain.Measurement;
import dev.draftwatch.domain.Provenance;
import dev.draftwatch.domain.SeedReport;
import dev.draftwatch.store.DetectionRecord;
import dev.draftwatch.store.ResultPointers;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * What {@code draftwatch report} shows (DECISIONS.md D69), built from stored results and
 * detection records alone. Every value it holds is a {@link Traced} value of a result file, so the
 * renderer can link each one to where it is stored (D68).
 */
public final class ReportModel {
  /** One detection outcome of a result, without the detector's numbers (D68). */
  public static final class Outcome {
    private final String kind;
    private final Optional<String> detector;
    private final Optional<String> metric;

    private Outcome(String kind, Optional<String> detector, Optional<String> metric) {
      this.kind = kind;
      this.detector = detector;
      this.metric = metric;
    }

    static Outcome of(DetectionRecord r) {
      return new Outcome(
          r.kind(), r.detector().map(d -> d.split("\\(", 2)[0]), r.metric());
    }

    /** {@code REGRESSION}, {@code ERROR}, {@code OK}, {@code INSUFFICIENT_DATA}, ... */
    public String kind() {
      return kind;
    }

    /** The detector's name, such as {@code paired_bootstrap}, without its parameters. */
    public Optional<String> detector() {
      return detector;
    }

    public Optional<String> metric() {
      return metric;
    }

    public boolean isRegression() {
      return kind.equals("REGRESSION");
    }

    public boolean isError() {
      return kind.equals("ERROR");
    }
  }

  /** One stored result. */
  public static final class Row {
    private final long stepValue;
    private final double alphaValue;
    private final double tauValue;
    private final Traced step;
    private final Traced alphaMean;
    private final Traced tauMean;
    private final Optional<Traced> alphaStd;
    private final Optional<Traced> tauStd;
    private final Traced jobId;
    private final Traced endTime;
    private final Path resultFile;
    private final Path rawReport;
    private final List<Outcome> outcomes;

    private Row(Measurement m, Path file, List<Outcome> outcomes) {
      Provenance p = m.provenance();
      this.stepValue = p.checkpoint().step();
      this.alphaValue = m.report().aggregate().alphaMean();
      this.tauValue = m.report().aggregate().tauMean();
      this.step = Traced.number(stepValue, file, ResultPointers.CHECKPOINT_STEP);
      this.alphaMean = Traced.number(alphaValue, file, ResultPointers.ALPHA_MEAN);
      this.tauMean = Traced.number(tauValue, file, ResultPointers.TAU_MEAN);
      this.alphaStd = traced(m.report().aggregate().alphaStd(), file, ResultPointers.ALPHA_STD);
      this.tauStd = traced(m.report().aggregate().tauStd(), file, ResultPointers.TAU_STD);
      this.jobId = Traced.of(p.jobId(), file, ResultPointers.JOB_ID);
      this.endTime = Traced.of(p.endTime().toString(), file, ResultPointers.END_TIME);
      this.resultFile = file;
      this.rawReport = p.rawReportPath();
      this.outcomes = List.copyOf(outcomes);
    }

    private static Optional<Traced> traced(OptionalDouble v, Path file, String pointer) {
      return v.isPresent()
          ? Optional.of(Traced.number(v.getAsDouble(), file, pointer))
          : Optional.empty();
    }

    public long stepValue() {
      return stepValue;
    }

    public double alphaValue() {
      return alphaValue;
    }

    public double tauValue() {
      return tauValue;
    }

    public Traced step() {
      return step;
    }

    public Traced alphaMean() {
      return alphaMean;
    }

    public Traced tauMean() {
      return tauMean;
    }

    /** Empty with one seed: the sample standard deviation is undefined. */
    public Optional<Traced> alphaStd() {
      return alphaStd;
    }

    public Optional<Traced> tauStd() {
      return tauStd;
    }

    public Traced jobId() {
      return jobId;
    }

    public Traced endTime() {
      return endTime;
    }

    public Path resultFile() {
      return resultFile;
    }

    /** The harness's report exactly as written ({@code raw/<job>/attempt-<n>/report.json}). */
    public Path rawReport() {
      return rawReport;
    }

    public List<Outcome> outcomes() {
      return outcomes;
    }

    public boolean hasRegression() {
      return outcomes.stream().anyMatch(Outcome::isRegression);
    }

    public boolean hasError() {
      return outcomes.stream().anyMatch(Outcome::isError);
    }
  }

  /** One position of one seed's {@code alpha_by_position}. */
  public static final class PositionValue {
    private final Traced position;
    private final Optional<Traced> alpha;
    private final OptionalDouble alphaValue;

    private PositionValue(Traced position, Optional<Traced> alpha, OptionalDouble alphaValue) {
      this.position = position;
      this.alpha = alpha;
      this.alphaValue = alphaValue;
    }

    public Traced position() {
      return position;
    }

    /** Empty when no step was eligible: the value is undefined ({@code null} in the file). */
    public Optional<Traced> alpha() {
      return alpha;
    }

    public OptionalDouble alphaValue() {
      return alphaValue;
    }
  }

  /** One seed's positional acceptance. */
  public static final class SeedPositions {
    private final Traced seed;
    private final boolean exact;
    private final List<PositionValue> positions;

    private SeedPositions(Traced seed, boolean exact, List<PositionValue> positions) {
      this.seed = seed;
      this.exact = exact;
      this.positions = List.copyOf(positions);
    }

    public Traced seed() {
      return seed;
    }

    /** {@code position_counts_exact}; when false the values are labeled approximate. */
    public boolean exact() {
      return exact;
    }

    public List<PositionValue> positions() {
      return positions;
    }
  }

  /** Positional acceptance of a series' latest checkpoint. */
  public static final class Positional {
    private final Row row;
    private final List<SeedPositions> seeds;

    private Positional(Row row, List<SeedPositions> seeds) {
      this.row = row;
      this.seeds = List.copyOf(seeds);
    }

    /** The result it comes from. */
    public Row row() {
      return row;
    }

    public List<SeedPositions> seeds() {
      return seeds;
    }
  }

  /** Results with one comparability key (D69), in step order. */
  public static final class Series {
    private final Traced probeId;
    private final Traced probeHash;
    private final Traced harnessVersion;
    private final Traced backend;
    private final Traced draftStructure;
    private final Traced gpu;
    private final Traced gpuCount;
    private final List<Row> rows;
    private final Positional latest;

    private Series(
        Traced probeId,
        Traced probeHash,
        Traced harnessVersion,
        Traced backend,
        Traced draftStructure,
        Traced gpu,
        Traced gpuCount,
        List<Row> rows,
        Positional latest) {
      this.probeId = probeId;
      this.probeHash = probeHash;
      this.harnessVersion = harnessVersion;
      this.backend = backend;
      this.draftStructure = draftStructure;
      this.gpu = gpu;
      this.gpuCount = gpuCount;
      this.rows = List.copyOf(rows);
      this.latest = latest;
    }

    public Traced probeId() {
      return probeId;
    }

    public Traced probeHash() {
      return probeHash;
    }

    public Traced harnessVersion() {
      return harnessVersion;
    }

    public Traced backend() {
      return backend;
    }

    public Traced draftStructure() {
      return draftStructure;
    }

    /** {@code hardware.gpu}: part of the comparability key (DECISIONS.md D89). */
    public Traced gpu() {
      return gpu;
    }

    public Traced gpuCount() {
      return gpuCount;
    }

    /** Step order, then end time; at least one. */
    public List<Row> rows() {
      return rows;
    }

    public Positional latest() {
      return latest;
    }

    /** True if any result has more than one seed, so standard deviations exist. */
    public boolean hasStd() {
      return rows.stream().anyMatch(r -> r.alphaStd().isPresent());
    }
  }

  /** One target's series. */
  public static final class TargetSection {
    private final Traced name;
    private final List<Series> series;

    private TargetSection(Traced name, List<Series> series) {
      this.name = name;
      this.series = List.copyOf(series);
    }

    public Traced name() {
      return name;
    }

    public List<Series> series() {
      return series;
    }
  }

  private static final Comparator<Measurement> ORDER =
      Comparator.comparingLong((Measurement m) -> m.provenance().checkpoint().step())
          .thenComparing(m -> m.provenance().endTime())
          .thenComparing(Measurement::jobId);

  private final List<TargetSection> targets;
  private final List<String> configuredWithoutResults;
  private final Path detectionsLog;

  private ReportModel(
      List<TargetSection> targets, List<String> configuredWithoutResults, Path detectionsLog) {
    this.targets = List.copyOf(targets);
    this.configuredWithoutResults = List.copyOf(configuredWithoutResults);
    this.detectionsLog = detectionsLog;
  }

  /**
   * Builds the model.
   *
   * @param configured target names in config order; stored targets not among them follow, sorted
   * @param results every stored result
   * @param locate where each result is stored
   * @param detections every detection record, oldest first
   * @param detectionsLog the file the records are read from, linked from each outcome
   */
  public static ReportModel build(
      List<String> configured,
      List<Measurement> results,
      Function<Measurement, Path> locate,
      List<DetectionRecord> detections,
      Path detectionsLog) {
    Map<String, List<Outcome>> outcomes = new LinkedHashMap<>();
    for (DetectionRecord r : detections) {
      outcomes.computeIfAbsent(r.jobId(), k -> new ArrayList<>()).add(Outcome.of(r));
    }
    Map<String, List<Measurement>> byTarget = new LinkedHashMap<>();
    for (Measurement m : results) {
      byTarget.computeIfAbsent(m.provenance().targetName(), k -> new ArrayList<>()).add(m);
    }
    Set<String> order = new LinkedHashSet<>(configured);
    order.addAll(new TreeSet<>(byTarget.keySet()));
    List<TargetSection> sections = new ArrayList<>();
    List<String> empty = new ArrayList<>();
    for (String target : order) {
      List<Measurement> ms = byTarget.get(target);
      if (ms == null) {
        empty.add(target);
        continue;
      }
      ms.sort(ORDER);
      sections.add(section(ms, locate, outcomes));
    }
    return new ReportModel(sections, empty, Objects.requireNonNull(detectionsLog));
  }

  private static TargetSection section(
      List<Measurement> ms,
      Function<Measurement, Path> locate,
      Map<String, List<Outcome>> outcomes) {
    Map<List<String>, List<Measurement>> byKey = new LinkedHashMap<>();
    for (Measurement m : ms) {
      byKey.computeIfAbsent(key(m), k -> new ArrayList<>()).add(m);
    }
    List<List<String>> keys = new ArrayList<>(byKey.keySet());
    keys.sort(Comparator.comparing((List<String> k) -> k.get(0)));
    List<Series> series = new ArrayList<>();
    for (List<String> key : keys) {
      series.add(series(byKey.get(key), locate, outcomes));
    }
    Measurement first = ms.get(0);
    return new TargetSection(
        Traced.of(
            first.provenance().targetName(), locate.apply(first), ResultPointers.TARGET),
        series);
  }

  /**
   * The comparability key of MEASUREMENT_CONTRACT.md, "Comparability", with the probe id; the
   * probe hash stands for everything it covers.
   */
  private static List<String> key(Measurement m) {
    Provenance p = m.provenance();
    return List.of(
        p.probeId(),
        p.probeHash(),
        p.harnessVersion(),
        p.backend(),
        m.report().draftStructure().wireName(),
        m.report().hardware().gpu(),
        Integer.toString(m.report().hardware().count()));
  }

  private static Series series(
      List<Measurement> ms,
      Function<Measurement, Path> locate,
      Map<String, List<Outcome>> outcomes) {
    List<Row> rows = new ArrayList<>();
    for (Measurement m : ms) {
      rows.add(new Row(m, locate.apply(m), outcomes.getOrDefault(m.jobId(), List.of())));
    }
    Measurement last = ms.get(ms.size() - 1); // highest step, then latest end time
    Path file = locate.apply(last);
    Provenance p = last.provenance();
    return new Series(
        Traced.of(p.probeId(), file, ResultPointers.PROBE_ID),
        Traced.of(p.probeHash(), file, ResultPointers.PROBE_HASH),
        Traced.of(p.harnessVersion(), file, ResultPointers.HARNESS_VERSION),
        Traced.of(p.backend(), file, ResultPointers.BACKEND),
        Traced.of(last.report().draftStructure().wireName(), file, ResultPointers.DRAFT_STRUCTURE),
        Traced.of(last.report().hardware().gpu(), file, ResultPointers.HARDWARE_GPU),
        Traced.number(
            (long) last.report().hardware().count(), file, ResultPointers.HARDWARE_COUNT),
        rows,
        positional(last, file, rows.get(rows.size() - 1)));
  }

  private static Positional positional(Measurement m, Path file, Row row) {
    List<SeedPositions> seeds = new ArrayList<>();
    List<SeedReport> reports = m.report().seeds();
    for (int i = 0; i < reports.size(); i++) {
      SeedReport s = reports.get(i);
      List<PositionValue> positions = new ArrayList<>();
      for (int k = 0; k < s.positionCounts().size(); k++) {
        OptionalDouble v = s.alphaByPosition().get(k);
        positions.add(
            new PositionValue(
                Traced.number(
                    (long) s.positionCounts().get(k).position(),
                    file,
                    ResultPointers.position(i, k)),
                v.isPresent()
                    ? Optional.of(
                        Traced.number(v.getAsDouble(), file, ResultPointers.alphaByPosition(i, k)))
                    : Optional.empty(),
                v));
      }
      seeds.add(
          new SeedPositions(
              Traced.number((long) s.seed(), file, ResultPointers.seed(i)),
              s.positionCountsExact(),
              positions));
    }
    return new Positional(row, seeds);
  }

  /** Targets with results, configured ones first. */
  public List<TargetSection> targets() {
    return targets;
  }

  /** Configured targets that have no stored result yet. */
  public List<String> configuredWithoutResults() {
    return configuredWithoutResults;
  }

  public Path detectionsLog() {
    return detectionsLog;
  }

  /** The number of results shown. */
  public int resultCount() {
    return targets.stream()
        .flatMap(t -> t.series().stream())
        .mapToInt(s -> s.rows().size())
        .sum();
  }
}
