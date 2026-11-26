package dev.draftwatch.scoring;

import dev.draftwatch.domain.AcceptanceReport;
import dev.draftwatch.domain.Measurement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Cost per measurement (docs/EVALUATION.md, "Metrics"): {@code wall_clock_seconds} from every
 * result and the job's elapsed time from the sacct dump, at the measured sizes (the runs' 100 and
 * 1,000 prompts, the pilot's 200). Sizes 25 and 50 come from an ordinary least-squares line of
 * seconds against N through the measured sizes' means, computed here because {@code
 * LeastSquaresSlope} fits against index, not N.
 */
public final class CostTable {
  static final List<Integer> EXTRAPOLATED = List.of(25, 50);

  private final List<CostRow> rows;
  private final List<String> missingElapsed;

  private CostTable(List<CostRow> rows, List<String> missingElapsed) {
    this.rows = List.copyOf(rows);
    this.missingElapsed = List.copyOf(missingElapsed);
  }

  /** The Slurm job name of a measurement's attempt. */
  static String jobName(Measurement m) {
    return "draftwatch-" + m.jobId() + "-a" + m.provenance().attempt();
  }

  private static final class Sizes {
    final Map<Integer, List<Double>> wall = new TreeMap<>();
    final Map<Integer, List<Double>> elapsed = new TreeMap<>();
    final Map<Integer, Set<String>> versions = new TreeMap<>();

    void add(int n, double wallClock, String version) {
      wall.computeIfAbsent(n, k -> new ArrayList<>()).add(wallClock);
      versions.computeIfAbsent(n, k -> new TreeSet<>()).add(version);
    }
  }

  public static CostTable of(
      List<Measurement> all, Map<String, Duration> elapsed, List<AcceptanceReport> pilotReports) {
    Sizes sizes = new Sizes();
    List<String> missing = new ArrayList<>();
    for (Measurement m : all) {
      int n = m.report().numPrompts();
      sizes.add(n, m.report().wallClockSeconds(), m.report().harnessVersion());
      Duration d = elapsed.get(jobName(m));
      if (d == null) {
        missing.add(jobName(m));
      } else {
        sizes.elapsed.computeIfAbsent(n, k -> new ArrayList<>()).add((double) d.getSeconds());
      }
    }
    for (AcceptanceReport r : pilotReports) {
      sizes.add(r.numPrompts(), r.wallClockSeconds(), r.harnessVersion());
    }
    Map<Integer, CostRow> rows = new TreeMap<>();
    Map<Integer, Double> wallMeans = new TreeMap<>();
    Map<Integer, Double> elapsedMeans = new TreeMap<>();
    for (Map.Entry<Integer, List<Double>> e : sizes.wall.entrySet()) {
      int n = e.getKey();
      double wall = mean(e.getValue());
      wallMeans.put(n, wall);
      OptionalDouble el = OptionalDouble.empty();
      if (sizes.elapsed.containsKey(n)) {
        el = OptionalDouble.of(mean(sizes.elapsed.get(n)));
        elapsedMeans.put(n, el.getAsDouble());
      }
      rows.put(n, new CostRow(n, false, OptionalDouble.of(wall), el, e.getValue().size(),
          sizes.versions.get(n)));
    }
    for (int n : EXTRAPOLATED) {
      if (!rows.containsKey(n)) {
        rows.put(n, new CostRow(n, true, fit(wallMeans, n), fit(elapsedMeans, n), 0, Set.of()));
      }
    }
    missing.sort(null);
    return new CostTable(new ArrayList<>(rows.values()), missing);
  }

  private static double mean(List<Double> xs) {
    double sum = 0;
    for (double x : xs) {
      sum += x;
    }
    return sum / xs.size();
  }

  /** The least-squares line through {@code (N, mean)} at {@code n}; empty with under 2 sizes. */
  private static OptionalDouble fit(Map<Integer, Double> points, int n) {
    if (points.size() < 2) {
      return OptionalDouble.empty();
    }
    double k = points.size();
    double sx = 0;
    double sy = 0;
    double sxy = 0;
    double sxx = 0;
    for (Map.Entry<Integer, Double> p : points.entrySet()) {
      double x = p.getKey();
      double y = p.getValue();
      sx += x;
      sy += y;
      sxy += x * y;
      sxx += x * x;
    }
    double slope = (k * sxy - sx * sy) / (k * sxx - sx * sx);
    double intercept = (sy - slope * sx) / k;
    return OptionalDouble.of(intercept + slope * n);
  }

  /** One row per size, ascending by N. */
  public List<CostRow> rows() {
    return rows;
  }

  /** Job names of measurements with no COMPLETED row in the dump, sorted. */
  public List<String> missingElapsed() {
    return missingElapsed;
  }
}
