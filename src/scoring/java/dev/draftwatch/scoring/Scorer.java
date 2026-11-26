package dev.draftwatch.scoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.draftwatch.domain.AcceptanceReport;
import dev.draftwatch.domain.Measurement;
import dev.draftwatch.harness.ReportJson;
import dev.draftwatch.stats.MetricCalculator;
import dev.draftwatch.store.AtomicFiles;
import dev.draftwatch.store.DetectionRecord;
import dev.draftwatch.store.FileBaselineRepository;
import dev.draftwatch.store.FileDetectionLog;
import dev.draftwatch.store.FileResultRepository;
import dev.draftwatch.store.JsonCodec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Scores the evaluation (docs/EVALUATION.md, "Scoring the detectors"): it loads and validates the
 * state directory, cross-checks the live log, labels every reference checkpoint, replays both
 * runs of every family, reference probe, size, and configuration, and writes {@code score.json},
 * {@code alarm_rates.csv}, and {@code manifest.json}. Nothing is written unless every step
 * succeeds.
 */
public final class Scorer {
  static final List<ProbeKind> REFERENCE = List.of(ProbeKind.CHAT, ProbeKind.CODE);
  static final int CHECKPOINT_RESAMPLES = 2000;
  static final double CHECKPOINT_CONFIDENCE = 0.95;
  static final long CHECKPOINT_SEED = 0;
  /** Expected B0 coverage of a nominal 95% interval, drawing from a finite pool (the spec). */
  static final Map<Integer, Double> EXPECTED_COVERAGE =
      Map.of(25, 0.953, 50, 0.956, 100, 0.961, 200, 0.972);
  static final String CSV_HEADER =
      "family,probe,config,n,run,step,delta_ref,lower,upper,label,flagged,subsets,rate";

  private final MetricCalculator calculator;
  private final ObjectMapper json;

  public Scorer(MetricCalculator calculator, ObjectMapper json) {
    this.calculator = Objects.requireNonNull(calculator, "calculator");
    this.json = Objects.requireNonNull(json, "json");
  }

  /** What the cross-check found, for the caller to print. */
  public static final class Summary {
    private final int compared;
    private final int trendMismatches;

    Summary(int compared, int trendMismatches) {
      this.compared = compared;
      this.trendMismatches = trendMismatches;
    }

    public int compared() {
      return compared;
    }

    public int trendMismatches() {
      return trendMismatches;
    }
  }

  /**
   * @throws DataException if {@code out} is not empty, an input is invalid or outside the root,
   *     or the cross-check fails
   */
  public Summary score(Path stateDir, Path out, ScoreOptions options) {
    requireEmpty(out);
    Manifest manifest = new Manifest(options.root());
    FileResultRepository results = new FileResultRepository(stateDir, new JsonCodec(), json);
    EvaluationData data = EvaluationData.load(results, new FileBaselineRepository(stateDir, json));
    List<Measurement> all = results.all();
    Map<String, String> resultFiles = new TreeMap<>();
    for (Measurement m : all) {
      resultFiles.put(m.jobId(), manifest.input(results.locate(m)));
    }
    manifest.input(stateDir.resolve(FileBaselineRepository.FILE));
    manifest.input(stateDir.resolve(FileDetectionLog.FILE));
    List<DetectionRecord> log = new FileDetectionLog(stateDir, json).all();
    List<DetectorConfig> configs = DetectorConfigs.all(calculator);
    LogCrossCheck.Result check = LogCrossCheck.check(data, log, configs);

    ObjectNode score = ScoreOutput.F.objectNode();
    score.set("tool", tool(options, configs));
    score.set("inputs", inputs(manifest, stateDir, all, data));
    score.set("cross_check", crossCheck(check));

    GroundTruth truth = new GroundTruth(calculator);
    Map<String, List<CheckpointRow>> rows = new TreeMap<>();
    ObjectNode groundTruth = score.putObject("ground_truth");
    for (String family : data.families()) {
      for (ProbeKind kind : REFERENCE) {
        String probe = family + "-" + kind.suffix();
        Measurement baseline = data.baseline(family, kind);
        List<CheckpointRow> probeRows = new ArrayList<>();
        ObjectNode node = groundTruth.putObject(probe);
        node.put("baseline_job_id", baseline.jobId());
        node.put("baseline_result_file", resultFiles.get(baseline.jobId()));
        ArrayNode list = node.putArray("checkpoints");
        for (Run run : Run.values()) {
          for (Measurement m : data.series(family, kind, run).inStepOrder()) {
            long step = m.provenance().checkpoint().step();
            if (step == 0) {
              continue;
            }
            ReferenceDifference ref = truth.reference(m, baseline);
            probeRows.add(CheckpointRow.of(run, step, ref));
            list.addObject()
                .put("run", run.suffix())
                .put("step", step)
                .put("delta", ref.delta())
                .put("lower", ref.lower())
                .put("upper", ref.upper())
                .put("label", ref.label().name())
                .put("tau_difference",
                    m.report().aggregate().tauMean() - baseline.report().aggregate().tauMean())
                .put("job_id", m.jobId())
                .put("result_file", resultFiles.get(m.jobId()));
          }
        }
        rows.put(probe, probeRows);
      }
    }

    List<String> csv = new ArrayList<>();
    csv.add(CSV_HEADER);
    score.set("scores", scores(data, rows, configs, options, csv));
    score.set("drift", drift(data));
    score.set("cost", cost(all, options, manifest));
    score.set("operation", operation(OperationSummary.of(data, log, rows)));

    byte[] scoreBytes = ScoreOutput.bytes(json, score);
    byte[] csvBytes = (String.join("\n", csv) + "\n").getBytes(StandardCharsets.UTF_8);
    Path scoreFile = out.resolve("score.json");
    Path csvFile = out.resolve("alarm_rates.csv");
    manifest.output(scoreFile, scoreBytes);
    manifest.output(csvFile, csvBytes);
    byte[] manifestBytes =
        ScoreOutput.bytes(json, manifest.toJson(options.toolCommit(), optionsJson(options, manifest,
            stateDir)));
    write(scoreFile, scoreBytes);
    write(csvFile, csvBytes);
    write(out.resolve("manifest.json"), manifestBytes);
    return new Summary(check.compared(), check.trendMismatches().size());
  }

  private static void requireEmpty(Path out) {
    if (!Files.exists(out)) {
      return;
    }
    try (Stream<Path> list = Files.list(out)) {
      if (list.findAny().isPresent()) {
        throw new DataException(out + ": output directory is not empty");
      }
    } catch (IOException e) {
      throw new DataException(out + ": cannot list: " + e.getMessage(), e);
    }
  }

  private static void write(Path file, byte[] content) {
    try {
      AtomicFiles.writeNew(file, content);
    } catch (IOException e) {
      throw new DataException(file + ": cannot write: " + e.getMessage(), e);
    }
  }

  private static ObjectNode tool(ScoreOptions o, List<DetectorConfig> configs) {
    ObjectNode n = ScoreOutput.F.objectNode();
    n.put("commit", o.toolCommit());
    n.put("delta", GroundTruth.DELTA);
    n.put("subsets_per_size", o.subsetsPerSize());
    ArrayNode sizes = n.putArray("sizes");
    o.sizes().forEach(sizes::add);
    n.put("subset_seed", "N (one java.util.Random(N) per size)");
    n.put("checkpoint_bootstrap",
        CHECKPOINT_RESAMPLES + " resamples, seed " + CHECKPOINT_SEED + ", "
            + CHECKPOINT_CONFIDENCE + " percentile interval over checkpoints");
    ObjectNode c = n.putObject("configurations");
    for (DetectorConfig config : configs) {
      c.put(config.id(), config.detector().describe());
    }
    return n;
  }

  private static ObjectNode inputs(
      Manifest manifest, Path stateDir, List<Measurement> all, EvaluationData data) {
    ObjectNode n = ScoreOutput.F.objectNode();
    n.put("state_dir", manifest.relative(stateDir));
    n.put("measurements", all.size());
    ArrayNode missing = n.putArray("missing");
    for (MissingMeasurement m : data.missing()) {
      missing.addObject().put("target", m.target()).put("probe_id", m.probeId())
          .put("step", m.step());
    }
    return n;
  }

  private static ObjectNode crossCheck(LogCrossCheck.Result check) {
    ObjectNode n = ScoreOutput.F.objectNode();
    n.put("compared", check.compared());
    ArrayNode exact = n.putArray("exact_detectors");
    exact.add("B0").add("A");
    ArrayNode t = n.putArray("trend_mismatches");
    check.trendMismatches().forEach(t::add);
    return n;
  }

  private static final class JobResult {
    final ObjectNode node;
    final List<String> csv;

    JobResult(ObjectNode node, List<String> csv) {
      this.node = node;
      this.csv = csv;
    }
  }

  private ObjectNode scores(
      EvaluationData data,
      Map<String, List<CheckpointRow>> rows,
      List<DetectorConfig> configs,
      ScoreOptions options,
      List<String> csv) {
    Replayer replayer = new Replayer(new SubsetMeasurements(calculator));
    List<String> keys = new ArrayList<>();
    List<Callable<JobResult>> jobs = new ArrayList<>();
    for (String family : data.families()) {
      for (ProbeKind kind : REFERENCE) {
        String probe = family + "-" + kind.suffix();
        Measurement baseline = data.baseline(family, kind);
        int pool = baseline.report().numPrompts();
        for (DetectorConfig config : configs) {
          for (int n : options.sizes()) {
            keys.add(probe + "\n" + config.id() + "\n" + n);
            jobs.add(
                () -> {
                  List<Subset> draw = SubsetDraw.draw(pool, n, options.subsetsPerSize(), n);
                  FlagMatrix run1 =
                      replayer.replay(baseline, data.series(family, kind, Run.RUN1), Run.RUN1,
                          draw, config);
                  FlagMatrix run2 =
                      replayer.replay(baseline, data.series(family, kind, Run.RUN2), Run.RUN2,
                          draw, config);
                  return metrics(family, probe, config.id(), n, rows.get(probe),
                      new DetectionMetrics(rows.get(probe), run1, run2));
                });
          }
        }
      }
    }
    List<JobResult> done = runAll(jobs, options.threads());
    ObjectNode scores = ScoreOutput.F.objectNode();
    for (int i = 0; i < keys.size(); i++) {
      String[] k = keys.get(i).split("\n");
      ObjectNode byConfig = scores.has(k[0]) ? (ObjectNode) scores.get(k[0]) : scores.putObject(k[0]);
      ObjectNode bySize = byConfig.has(k[1]) ? (ObjectNode) byConfig.get(k[1]) : byConfig.putObject(k[1]);
      bySize.set(k[2], done.get(i).node);
      csv.addAll(done.get(i).csv);
    }
    return scores;
  }

  private static List<JobResult> runAll(List<Callable<JobResult>> jobs, int threads) {
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      List<Future<JobResult>> futures = new ArrayList<>();
      for (Callable<JobResult> job : jobs) {
        futures.add(pool.submit(job));
      }
      List<JobResult> out = new ArrayList<>();
      for (Future<JobResult> f : futures) {
        out.add(f.get());
      }
      return out;
    } catch (ExecutionException e) {
      if (e.getCause() instanceof RuntimeException) {
        throw (RuntimeException) e.getCause();
      }
      throw new IllegalStateException(e.getCause());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while scoring", e);
    } finally {
      pool.shutdownNow();
    }
  }

  private static JobResult metrics(
      String family, String probe, String id, int n, List<CheckpointRow> rows,
      DetectionMetrics m) {
    ObjectNode node = ScoreOutput.F.objectNode();
    List<String> csv = new ArrayList<>();
    List<Rate> alarm = m.alarmRates();
    ArrayNode rates = node.putArray("alarm_rates");
    for (int i = 0; i < rows.size(); i++) {
      CheckpointRow r = rows.get(i);
      Rate a = alarm.get(i);
      String rate = Double.toString(a.value().getAsDouble());
      rates.addObject()
          .put("run", r.run().suffix())
          .put("step", r.step())
          .put("delta_ref", r.reference().delta())
          .put("lower", r.reference().lower())
          .put("upper", r.reference().upper())
          .put("label", r.label().name())
          .put("flagged", a.flagged())
          .put("subsets", a.total())
          .put("rate", a.value().getAsDouble());
      csv.add(String.join(",", family, probe, id, Integer.toString(n), r.run().suffix(),
          Long.toString(r.step()), Double.toString(r.reference().delta()),
          Double.toString(r.reference().lower()), Double.toString(r.reference().upper()),
          r.label().name(), Integer.toString(a.flagged()), Integer.toString(a.total()), rate));
    }
    List<CheckpointRow> notRegressed = labeled(rows, Label.NOT_REGRESSED);
    node.set("false_alarm_rate",
        ScoreOutput.rate(m.falseAlarmRate(), interval(rows, alarm, notRegressed),
            "no NOT_REGRESSED checkpoints"));
    node.set("detection_rate",
        ScoreOutput.rate(m.detectionRate(), interval(rows, alarm, labeled(rows, Label.REGRESSED)),
            "no REGRESSED checkpoints"));
    String perRun = "one value per run; no interval";
    node.set("run_false_alarm_rate", ScoreOutput.rate(m.runFalseAlarmRate(), Optional.empty(),
        perRun));
    node.set("any_run1_alarm_rate", ScoreOutput.rate(m.anyRun1AlarmRate(), Optional.empty(),
        perRun));
    node.set("delay", delay(m.delay()));
    if (id.equals("B0")) {
      List<CheckpointRow> drops = m.realSmallDrops();
      List<CheckpointRow> kept = new ArrayList<>(notRegressed);
      kept.removeAll(drops);
      node.set("false_alarm_rate_excluding_real_drops",
          ScoreOutput.rate(m.falseAlarmRateExcludingRealDrops(), interval(rows, alarm, kept),
              "no NOT_REGRESSED checkpoints with U >= 0"));
      ArrayNode list = node.putArray("real_small_drops");
      for (CheckpointRow r : drops) {
        Rate a = alarm.get(rows.indexOf(r));
        list.addObject()
            .put("run", r.run().suffix())
            .put("step", r.step())
            .put("delta_ref", r.reference().delta())
            .put("upper", r.reference().upper())
            .put("alarm_rate", a.value().getAsDouble());
      }
      List<Rate> covered = m.coverageByCheckpoint();
      ObjectNode coverage =
          ScoreOutput.rate(m.coverage(), interval(rows, covered, rows), "no compared checkpoints");
      Double expected = EXPECTED_COVERAGE.get(n);
      if (expected == null) {
        coverage.putNull("expected");
      } else {
        coverage.put("expected", expected);
      }
      node.set("coverage", coverage);
    }
    return new JobResult(node, csv);
  }

  private static List<CheckpointRow> labeled(List<CheckpointRow> rows, Label label) {
    return rows.stream().filter(r -> r.label() == label).collect(Collectors.toList());
  }

  /** The checkpoint-bootstrap interval of the rate pooled over {@code over}. */
  private static Optional<double[]> interval(
      List<CheckpointRow> rows, List<Rate> perRow, List<CheckpointRow> over) {
    int[] flagged = new int[over.size()];
    int[] total = new int[over.size()];
    for (int i = 0; i < over.size(); i++) {
      Rate r = perRow.get(rows.indexOf(over.get(i)));
      flagged[i] = r.flagged();
      total[i] = r.total();
    }
    return CheckpointBootstrap.interval(
        flagged, total, CHECKPOINT_RESAMPLES, CHECKPOINT_CONFIDENCE, CHECKPOINT_SEED);
  }

  private static ObjectNode delay(Delay d) {
    ObjectNode n = ScoreOutput.F.objectNode();
    n.put("status", d.status().name());
    ScoreOutput.put(n, "onset_step", d.onsetStep());
    ScoreOutput.put(n, "median_steps", d.medianSteps());
    if (d.medianSteps().isEmpty()) {
      n.put("median_reason",
          d.status() == Delay.Status.SCORED
              ? "more than half the subsets miss"
              : "not scored: " + d.status().name());
    }
    ScoreOutput.put(n, "miss_fraction", d.missFraction());
    ScoreOutput.put(n, "early_fraction", d.earlyFraction());
    ScoreOutput.put(n, "false_alarm_before_onset_fraction", d.falseAlarmBeforeOnsetFraction());
    ArrayNode failed = n.putArray("control_failed_steps");
    d.controlFailedSteps().forEach(failed::add);
    n.putNull("interval");
    n.put("interval_reason", "one onset per run; no interval");
    return n;
  }

  private static ObjectNode drift(EvaluationData data) {
    ObjectNode n = ScoreOutput.F.objectNode();
    for (String family : data.families()) {
      for (ProbeKind kind : REFERENCE) {
        Series run1 = data.series(family, kind, Run.RUN1);
        Series run2 = data.series(family, kind, Run.RUN2);
        ArrayNode points = n.putArray(family + "-" + kind.suffix());
        for (DriftPoint p : Drift.of(run1, run2)) {
          points.addObject()
              .put("step", p.step())
              .put("run2_minus_run1_alpha", p.run2MinusRun1Alpha())
              .put("run1_job_id", run1.at(p.step()).get().jobId())
              .put("run2_job_id", run2.at(p.step()).get().jobId());
        }
      }
    }
    return n;
  }

  private ObjectNode cost(List<Measurement> all, ScoreOptions o, Manifest manifest) {
    Map<String, Duration> elapsed = Map.of();
    if (o.sacct().isPresent()) {
      manifest.input(o.sacct().get());
      elapsed = SacctDump.parse(o.sacct().get());
    }
    List<AcceptanceReport> pilot = new ArrayList<>();
    if (o.pilotDir().isPresent()) {
      for (Path file : jsonFiles(o.pilotDir().get())) {
        manifest.input(file);
        pilot.add(readReport(file));
      }
    }
    CostTable table = CostTable.of(all, elapsed, pilot);
    ObjectNode n = ScoreOutput.F.objectNode();
    if (o.sacct().isPresent()) {
      n.put("sacct", manifest.relative(o.sacct().get()));
      ArrayNode missing = n.putArray("missing_elapsed");
      table.missingElapsed().forEach(missing::add);
    } else {
      n.putNull("sacct");
    }
    if (o.pilotDir().isPresent()) {
      n.put("pilot_reports", manifest.relative(o.pilotDir().get()));
    } else {
      n.putNull("pilot_reports");
    }
    ArrayNode rows = n.putArray("rows");
    for (CostRow r : table.rows()) {
      ObjectNode row = rows.addObject();
      row.put("n", r.n());
      row.put("extrapolated", r.extrapolated());
      ScoreOutput.put(row, "mean_wall_clock_seconds", r.meanWallClockSeconds());
      ScoreOutput.put(row, "mean_elapsed_seconds", r.meanElapsedSeconds());
      row.put("measurements", r.measurements());
      ArrayNode versions = row.putArray("harness_versions");
      r.harnessVersions().stream().sorted().forEach(versions::add);
    }
    return n;
  }

  private static List<Path> jsonFiles(Path dir) {
    try (Stream<Path> list = Files.list(dir)) {
      return list.filter(p -> p.getFileName().toString().endsWith(".json"))
          .sorted()
          .collect(Collectors.toList());
    } catch (IOException e) {
      throw new DataException(dir + ": cannot list: " + e.getMessage(), e);
    }
  }

  private AcceptanceReport readReport(Path file) {
    try {
      JsonNode root = json.readTree(file.toFile());
      ReportJson.checkShape(root);
      return ReportJson.toReport(root);
    } catch (IOException | RuntimeException e) {
      throw new DataException(file + ": not a harness report: " + e.getMessage(), e);
    }
  }

  private static ObjectNode operation(OperationSummary s) {
    ObjectNode n = ScoreOutput.F.objectNode();
    n.put("measurements_per_checkpoint_max", s.measurementsPerCheckpointMax());
    ArrayNode remeasured = n.putArray("remeasured");
    s.remeasured().forEach(remeasured::add);
    ArrayNode missing = n.putArray("missing");
    for (MissingMeasurement m : s.missing()) {
      missing.addObject().put("target", m.target()).put("probe_id", m.probeId())
          .put("step", m.step());
    }
    ArrayNode flags = n.putArray("live_flags");
    for (LiveFlag f : s.liveFlags()) {
      flags.addObject()
          .put("target", f.target())
          .put("probe_id", f.probeId())
          .put("step", f.step())
          .put("detector", f.detector())
          .put("job_id", f.jobId())
          .put("reference_label", f.referenceLabel());
    }
    return n;
  }

  private static ObjectNode optionsJson(ScoreOptions o, Manifest manifest, Path stateDir) {
    ObjectNode n = ScoreOutput.F.objectNode();
    n.put("state", manifest.relative(stateDir));
    ArrayNode sizes = n.putArray("sizes");
    o.sizes().forEach(sizes::add);
    n.put("subsets_per_size", o.subsetsPerSize());
    if (o.sacct().isPresent()) {
      n.put("sacct", manifest.relative(o.sacct().get()));
    } else {
      n.putNull("sacct");
    }
    if (o.pilotDir().isPresent()) {
      n.put("pilot", manifest.relative(o.pilotDir().get()));
    } else {
      n.putNull("pilot");
    }
    return n;
  }
}
