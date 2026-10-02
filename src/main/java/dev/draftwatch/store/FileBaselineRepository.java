package dev.draftwatch.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.draftwatch.domain.Baseline;
import dev.draftwatch.domain.WireNamed;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * {@code <state>/baselines.json}: target name → baseline checkpoint, replaced atomically on every
 * change.
 */
public final class FileBaselineRepository implements BaselineRepository {
  public static final String FILE = "baselines.json";

  private final Path file;
  private final ObjectMapper json;

  public FileBaselineRepository(Path stateDir, ObjectMapper json) {
    this.file = stateDir.resolve(FILE);
    this.json = Objects.requireNonNull(json, "json");
  }

  @Override
  public Optional<Baseline> get(String target) {
    return Optional.ofNullable(read().get(target));
  }

  @Override
  public void set(Baseline baseline) {
    Map<String, Baseline> all = read();
    all.put(baseline.targetName(), baseline);
    ObjectNode root = json.createObjectNode();
    for (Baseline b : all.values()) {
      ObjectNode entry = root.putObject(b.targetName());
      entry.put("fingerprint", b.fingerprint());
      entry.put("path", b.path().toString());
      entry.put("step", b.step());
      entry.put("set_at", b.setAt().toString());
      entry.put("source", b.source().wireName());
    }
    try {
      AtomicFiles.write(file, json.writerWithDefaultPrettyPrinter().writeValueAsBytes(root));
    } catch (IOException e) {
      throw new StoreException(file, "cannot write baselines: " + e.getMessage(), e);
    }
  }

  private Map<String, Baseline> read() {
    Map<String, Baseline> all = new TreeMap<>();
    if (!Files.exists(file)) {
      return all;
    }
    JsonNode root;
    try {
      root = json.readTree(file.toFile());
    } catch (IOException e) {
      throw new StoreException(file, "is not valid JSON: " + e.getMessage(), e);
    }
    try {
      for (Map.Entry<String, JsonNode> e : new Fields(root, "").entries()) {
        Fields f = new Fields(e.getValue(), e.getKey());
        all.put(
            e.getKey(),
            Baseline.of(
                e.getKey(),
                f.text("fingerprint"),
                f.path("path"),
                f.longValue("step"),
                f.instant("set_at"),
                WireNamed.parse(Baseline.Source.class, f.text("source"))
                    .orElseThrow(
                        () -> new IllegalArgumentException(e.getKey() + ".source is unknown"))));
      }
    } catch (RuntimeException e) {
      throw new StoreException(file, "is not a valid baselines file: " + e.getMessage(), e);
    }
    return all;
  }
}
