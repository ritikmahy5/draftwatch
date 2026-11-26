package dev.draftwatch.scoring;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.draftwatch.fingerprint.Sha256;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/**
 * Every file the scoring read or wrote, by path relative to the repository root and SHA-256, so
 * each reported number traces to a committed file. A file outside the root cannot be traced and
 * is refused.
 */
public final class Manifest {
  private final Path root;
  private final Map<String, String> inputs = new TreeMap<>();
  private final Map<String, String> outputs = new TreeMap<>();

  public Manifest(Path root) {
    this.root = root.toAbsolutePath().normalize();
  }

  /**
   * {@code file} relative to the root, with forward slashes.
   *
   * @throws DataException if it lies outside the root
   */
  public String relative(Path file) {
    Path abs = file.toAbsolutePath().normalize();
    if (!abs.startsWith(root)) {
      throw new DataException(abs + " is outside the repository root " + root
          + "; numbers read from it could not be traced");
    }
    return root.relativize(abs).toString().replace('\\', '/');
  }

  /** Records an input file and returns its relative path. */
  public String input(Path file) {
    String path = relative(file);
    inputs.put(path, sha256(file));
    return path;
  }

  /** Records an output file by its name, relative to the manifest's own directory. */
  public void output(Path file, byte[] content) {
    outputs.put(file.getFileName().toString(), Sha256.hex(content));
  }

  private static String sha256(Path file) {
    try {
      return Sha256.hex(Files.readAllBytes(file));
    } catch (IOException e) {
      throw new DataException(file + ": cannot read: " + e.getMessage(), e);
    }
  }

  ObjectNode toJson(String toolCommit, ObjectNode options) {
    JsonNodeFactory f = JsonNodeFactory.instance;
    ObjectNode node = f.objectNode();
    node.put("tool_commit", toolCommit);
    node.set("options", options);
    node.set("inputs", list(inputs));
    node.set("outputs", list(outputs));
    return node;
  }

  private static ArrayNode list(Map<String, String> files) {
    ArrayNode a = JsonNodeFactory.instance.arrayNode();
    for (Map.Entry<String, String> e : files.entrySet()) {
      a.addObject().put("path", e.getKey()).put("sha256", e.getValue());
    }
    return a;
  }
}
