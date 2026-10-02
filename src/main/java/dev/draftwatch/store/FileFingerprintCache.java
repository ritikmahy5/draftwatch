package dev.draftwatch.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.draftwatch.fingerprint.FingerprintCache;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * {@code <state>/fingerprints.json}: the fingerprint cache, replaced atomically on every change.
 * Writers that do not hold the state lock (for example {@code validate}) can only lose entries,
 * which are then recomputed; they cannot corrupt the file.
 */
public final class FileFingerprintCache implements FingerprintCache {
  public static final String FILE = "fingerprints.json";

  private final Path file;
  private final ObjectMapper json;

  public FileFingerprintCache(Path stateDir, ObjectMapper json) {
    this.file = stateDir.resolve(FILE);
    this.json = Objects.requireNonNull(json, "json");
  }

  @Override
  public Optional<Entry> get(String key) {
    return Optional.ofNullable(read().get(key));
  }

  @Override
  public void put(String key, Entry entry) {
    Map<String, Entry> all = read();
    all.put(key, entry);
    ObjectNode root = json.createObjectNode();
    for (Map.Entry<String, Entry> e : all.entrySet()) {
      ObjectNode node = root.putObject(e.getKey());
      node.put("signature", e.getValue().signature());
      node.put("fingerprint", e.getValue().fingerprint());
    }
    try {
      AtomicFiles.write(file, json.writerWithDefaultPrettyPrinter().writeValueAsBytes(root));
    } catch (IOException e) {
      throw new StoreException(file, "cannot write the fingerprint cache: " + e.getMessage(), e);
    }
  }

  private Map<String, Entry> read() {
    Map<String, Entry> all = new TreeMap<>();
    if (!Files.exists(file)) {
      return all;
    }
    try {
      JsonNode root = json.readTree(file.toFile());
      for (Map.Entry<String, JsonNode> e : new Fields(root, "").entries()) {
        Fields f = new Fields(e.getValue(), e.getKey());
        all.put(e.getKey(), new Entry(f.text("signature"), f.text("fingerprint")));
      }
    } catch (IOException | RuntimeException e) {
      throw new StoreException(
          file,
          "is not a valid fingerprint cache (" + e.getMessage() + "); delete it to rebuild",
          e);
    }
    return all;
  }
}
