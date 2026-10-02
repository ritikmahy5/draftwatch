package dev.draftwatch.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.draftwatch.events.DetectionSubject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * {@code <state>/detections.log}: one JSON object per line, append-only (DECISIONS.md D49). Each
 * record is written with a single {@code O_APPEND} write. JSON lines rather than prose, because
 * the log is read back: for deferred detections now, for report markers in M6.
 */
public final class FileDetectionLog implements DetectionLog {
  public static final String FILE = "detections.log";

  private final Path file;
  private final ObjectMapper json;

  public FileDetectionLog(Path stateDir, ObjectMapper json) {
    this.file = stateDir.resolve(FILE);
    this.json = Objects.requireNonNull(json, "json");
  }

  @Override
  public void append(DetectionRecord r) {
    ObjectNode node = json.createObjectNode();
    node.put("at", r.at().toString());
    node.put("kind", r.kind());
    DetectionSubject s = r.subject();
    node.put("target", s.target());
    node.put("probe_id", s.probeId());
    node.put("probe_hash", s.probeHash());
    node.put("step", s.step());
    node.put("fingerprint", s.fingerprint());
    node.put("job_id", s.jobId());
    node.put("result_file", s.resultFile().toString());
    putText(node, "detector", r.detector());
    putText(node, "metric", r.metric());
    putNumber(node, "observed", r.observed());
    putNumber(node, "threshold", r.threshold());
    putNumber(node, "interval_lower", r.intervalLower());
    putNumber(node, "interval_upper", r.intervalUpper());
    putText(node, "baseline_job_id", r.baselineJobId());
    node.put("explanation", r.explanation());
    try {
      Files.createDirectories(file.toAbsolutePath().getParent());
      byte[] line = (json.writeValueAsString(node) + "\n").getBytes(StandardCharsets.UTF_8);
      Files.write(
          file,
          line,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND,
          StandardOpenOption.WRITE);
    } catch (IOException e) {
      throw new StoreException(file, "cannot append detection: " + e.getMessage(), e);
    }
  }

  @Override
  public List<DetectionRecord> all() {
    List<DetectionRecord> records = new ArrayList<>();
    if (!Files.exists(file)) {
      return records;
    }
    List<String> lines;
    try {
      lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new StoreException(file, "cannot read: " + e.getMessage(), e);
    }
    for (int i = 0; i < lines.size(); i++) {
      if (lines.get(i).isEmpty()) {
        continue;
      }
      try {
        records.add(parse(json.readTree(lines.get(i))));
      } catch (IOException | RuntimeException e) {
        throw new StoreException(file, "line " + (i + 1) + " is not a detection record: " + e, e);
      }
    }
    return records;
  }

  private static DetectionRecord parse(JsonNode node) {
    Fields f = new Fields(node, "");
    return new DetectionRecord(
        f.instant("at"),
        f.text("kind"),
        DetectionSubject.of(
            f.text("target"),
            f.text("probe_id"),
            f.text("probe_hash"),
            f.longValue("step"),
            f.text("fingerprint"),
            f.text("job_id"),
            f.path("result_file")),
        f.optionalText("detector"),
        f.optionalText("metric"),
        number(node, "observed"),
        number(node, "threshold"),
        number(node, "interval_lower"),
        number(node, "interval_upper"),
        f.optionalText("baseline_job_id"),
        f.text("explanation"));
  }

  private static OptionalDouble number(JsonNode node, String key) {
    JsonNode v = node.get(key);
    if (v == null || v.isNull()) {
      return OptionalDouble.empty();
    }
    if (!v.isNumber()) {
      throw new IllegalArgumentException(key + " is not a number");
    }
    return OptionalDouble.of(v.doubleValue());
  }

  private static void putText(ObjectNode node, String key, Optional<String> value) {
    if (value.isPresent()) {
      node.put(key, value.get());
    } else {
      node.putNull(key);
    }
  }

  private static void putNumber(ObjectNode node, String key, OptionalDouble value) {
    if (value.isPresent()) {
      node.put(key, value.getAsDouble());
    } else {
      node.putNull(key);
    }
  }
}
