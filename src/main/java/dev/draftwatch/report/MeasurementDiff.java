package dev.draftwatch.report;

import com.fasterxml.jackson.databind.JsonNode;
import dev.draftwatch.detect.Comparability;
import dev.draftwatch.domain.Measurement;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Two stored results side by side (SPEC.md F7). Every value is the text of the
 * stored JSON value, so nothing shown is computed; a value one side lacks is shown as {@code -}.
 */
public final class MeasurementDiff {
  /** One stored result: its file, its contents, and the other results it was chosen over. */
  public static final class Side {
    private final Measurement measurement;
    private final Path file;
    private final JsonNode json;
    private final List<String> otherJobs;

    private Side(Measurement measurement, Path file, JsonNode json, List<String> otherJobs) {
      this.measurement = measurement;
      this.file = file;
      this.json = json;
      this.otherJobs = otherJobs;
    }

    /**
     * @param json the result file's contents, as read from {@code file}
     * @param otherJobs job ids of older results for the same checkpoint and probe
     */
    public static Side of(Measurement m, Path file, JsonNode json, List<String> otherJobs) {
      return new Side(
          Objects.requireNonNull(m, "m"),
          Objects.requireNonNull(file, "file"),
          Objects.requireNonNull(json, "json"),
          List.copyOf(otherJobs));
    }

    public Measurement measurement() {
      return measurement;
    }

    public Path file() {
      return file;
    }

    public List<String> otherJobs() {
      return otherJobs;
    }
  }

  /** A field and its value on each side. */
  public static final class Line {
    private final String field;
    private final String a;
    private final String b;

    private Line(String field, String a, String b) {
      this.field = field;
      this.a = a;
      this.b = b;
    }

    public String field() {
      return field;
    }

    public String a() {
      return a;
    }

    public String b() {
      return b;
    }
  }

  static final String ABSENT = "-";

  private static final List<String> AGGREGATE =
      List.of("alpha_mean", "alpha_std", "tau_mean", "tau_std");
  private static final List<String> SEED =
      List.of(
          "seed",
          "alpha",
          "tau",
          "total_steps",
          "total_proposed",
          "total_accepted",
          "excluded_prompts",
          "position_counts_exact");

  private final Side a;
  private final Side b;
  private final List<Line> metrics;
  private final List<Line> differences;
  private final Optional<String> incomparable;

  private MeasurementDiff(
      Side a, Side b, List<Line> metrics, List<Line> differences, Optional<String> incomparable) {
    this.a = a;
    this.b = b;
    this.metrics = List.copyOf(metrics);
    this.differences = List.copyOf(differences);
    this.incomparable = incomparable;
  }

  public static MeasurementDiff of(Side a, Side b) {
    List<Line> metrics = new ArrayList<>();
    for (String key : AGGREGATE) {
      String pointer = "/report/aggregate/" + key;
      metrics.add(line(key, a.json.at(pointer), b.json.at(pointer)));
    }
    int seeds = Math.max(a.json.at("/report/seeds").size(), b.json.at("/report/seeds").size());
    for (int i = 0; i < seeds; i++) {
      String prefix = "seeds[" + i + "].";
      for (String key : SEED) {
        String pointer = "/report/seeds/" + i + "/" + key;
        metrics.add(line(prefix + key, a.json.at(pointer), b.json.at(pointer)));
      }
      String positions = "/report/seeds/" + i + "/alpha_by_position";
      int k = Math.max(a.json.at(positions).size(), b.json.at(positions).size());
      for (int p = 0; p < k; p++) {
        metrics.add(
            line(
                prefix + "alpha_by_position[" + p + "]",
                a.json.at(positions + "/" + p),
                b.json.at(positions + "/" + p)));
      }
    }
    Map<String, String> fa = metadata(a.json);
    Map<String, String> fb = metadata(b.json);
    Set<String> fields = new LinkedHashSet<>(fa.keySet());
    fields.addAll(fb.keySet());
    List<Line> differences = new ArrayList<>();
    for (String field : fields) {
      String va = fa.getOrDefault(field, ABSENT);
      String vb = fb.getOrDefault(field, ABSENT);
      if (!va.equals(vb)) {
        differences.add(new Line(field, va, vb));
      }
    }
    return new MeasurementDiff(
        a, b, metrics, differences, Comparability.mismatch(a.measurement, b.measurement));
  }

  private static Line line(String field, JsonNode a, JsonNode b) {
    return new Line(field, text(a), text(b));
  }

  /** The stored value's JSON text, {@code null} for JSON null, {@link #ABSENT} if missing. */
  private static String text(JsonNode n) {
    if (n.isMissingNode()) {
      return ABSENT;
    }
    return n.isValueNode() ? (n.isTextual() ? n.textValue() : n.toString()) : n.toString();
  }

  /** {@code provenance} and the report outside {@code seeds} and {@code aggregate}, flattened. */
  private static Map<String, String> metadata(JsonNode json) {
    Map<String, String> out = new LinkedHashMap<>();
    flatten("provenance", json.get("provenance"), out);
    for (Map.Entry<String, JsonNode> e : json.get("report").properties()) {
      if (!e.getKey().equals("seeds") && !e.getKey().equals("aggregate")) {
        flatten("report." + e.getKey(), e.getValue(), out);
      }
    }
    return out;
  }

  private static void flatten(String prefix, JsonNode n, Map<String, String> out) {
    if (n.isObject()) {
      for (Map.Entry<String, JsonNode> e : n.properties()) {
        flatten(prefix + "." + e.getKey(), e.getValue(), out);
      }
    } else {
      out.put(prefix, text(n));
    }
  }

  public Side a() {
    return a;
  }

  public Side b() {
    return b;
  }

  /** The aggregate, then each seed's metrics, exactly as stored. */
  public List<Line> metrics() {
    return metrics;
  }

  /** Every provenance and report metadata field whose stored values differ. */
  public List<Line> differences() {
    return differences;
  }

  /** The first Comparability field that differs, if the two are not comparable. */
  public Optional<String> incomparable() {
    return incomparable;
  }
}
