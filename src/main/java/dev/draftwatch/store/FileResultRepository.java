package dev.draftwatch.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.draftwatch.domain.Measurement;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Results as {@code <state>/results/<target>/<fingerprint>__<probe-hash>__<job-id>.json}
 * (ARCHITECTURE.md, "Persistence"). Files are written once and never replaced.
 */
public final class FileResultRepository implements ResultRepository {
  private static final String SEPARATOR = "__";

  private static final Comparator<Measurement> BY_END_TIME =
      Comparator.comparing((Measurement m) -> m.provenance().endTime())
          .thenComparing(Measurement::jobId);
  private static final Comparator<Measurement> BY_STEP =
      Comparator.comparingLong((Measurement m) -> m.provenance().checkpoint().step())
          .thenComparing(BY_END_TIME);

  private final Path dir;
  private final JsonCodec codec;
  private final ObjectMapper json;

  public FileResultRepository(Path stateDir, JsonCodec codec, ObjectMapper json) {
    this.dir = stateDir.resolve("results");
    this.codec = Objects.requireNonNull(codec, "codec");
    this.json = Objects.requireNonNull(json, "json");
  }

  @Override
  public Path locate(Measurement m) {
    return dir.resolve(m.provenance().targetName())
        .resolve(m.fingerprint() + SEPARATOR + m.probeHash() + SEPARATOR + m.jobId() + ".json");
  }

  @Override
  public void append(Measurement m) {
    Path file = locate(m);
    byte[] content;
    try {
      content = json.writerWithDefaultPrettyPrinter().writeValueAsBytes(codec.measurementJson(m));
    } catch (IOException e) {
      throw new StoreException(file, "cannot serialize result: " + e.getMessage(), e);
    }
    try {
      AtomicFiles.writeNew(file, content);
    } catch (FileAlreadyExistsException e) {
      byte[] existing;
      try {
        existing = Files.readAllBytes(file);
      } catch (IOException read) {
        throw new StoreException(file, "exists and cannot be read: " + read.getMessage(), read);
      }
      if (!Arrays.equals(existing, content)) {
        throw new StoreException(
            file, "a different result is already stored for job " + m.jobId()
                + "; results are append-only");
      }
    } catch (IOException e) {
      throw new StoreException(file, "cannot write result: " + e.getMessage(), e);
    }
  }

  @Override
  public List<Measurement> history(String target, String probeHash) {
    List<Measurement> out = new ArrayList<>();
    for (Path file : files(dir.resolve(target))) {
      if (probeHashOf(file).equals(probeHash)) {
        out.add(read(file));
      }
    }
    out.sort(BY_STEP);
    return out;
  }

  @Override
  public List<Measurement> find(String fingerprint, String probeHash) {
    List<Measurement> out = new ArrayList<>();
    for (Path targetDir : directories()) {
      for (Path file : files(targetDir)) {
        String[] parts = parts(file);
        if (parts[0].equals(fingerprint) && parts[1].equals(probeHash)) {
          out.add(read(file));
        }
      }
    }
    out.sort(BY_END_TIME);
    return out;
  }

  @Override
  public List<Measurement> all() {
    List<Measurement> out = new ArrayList<>();
    for (Path targetDir : directories()) {
      List<Measurement> target = new ArrayList<>();
      for (Path file : files(targetDir)) {
        target.add(read(file));
      }
      target.sort(BY_STEP);
      out.addAll(target);
    }
    return out;
  }

  @Override
  public Optional<Measurement> latest(String fingerprint, String probeHash) {
    List<Measurement> all = find(fingerprint, probeHash);
    return all.isEmpty() ? Optional.empty() : Optional.of(all.get(all.size() - 1));
  }

  private List<Path> directories() {
    if (!Files.isDirectory(dir)) {
      return List.of();
    }
    try (Stream<Path> list = Files.list(dir)) {
      return list.filter(Files::isDirectory).sorted().collect(Collectors.toList());
    } catch (IOException e) {
      throw new StoreException(dir, "cannot list results: " + e.getMessage(), e);
    }
  }

  private static List<Path> files(Path targetDir) {
    if (!Files.isDirectory(targetDir)) {
      return List.of();
    }
    try (Stream<Path> list = Files.list(targetDir)) {
      return list.filter(p -> p.getFileName().toString().endsWith(".json"))
          .filter(p -> !p.getFileName().toString().startsWith("."))
          .sorted()
          .collect(Collectors.toList());
    } catch (IOException e) {
      throw new StoreException(targetDir, "cannot list results: " + e.getMessage(), e);
    }
  }

  /** {@code [fingerprint, probe hash, job id]} from the file name. */
  private static String[] parts(Path file) {
    String name = file.getFileName().toString();
    String[] parts = name.substring(0, name.length() - ".json".length()).split(SEPARATOR, -1);
    if (parts.length != 3) {
      throw new StoreException(file, "is not named <fingerprint>__<probe-hash>__<job-id>.json");
    }
    return parts;
  }

  private static String probeHashOf(Path file) {
    return parts(file)[1];
  }

  private Measurement read(Path file) {
    JsonNode node;
    try {
      node = json.readTree(file.toFile());
    } catch (IOException e) {
      throw new StoreException(file, "is not valid JSON: " + e.getMessage(), e);
    }
    Measurement m;
    try {
      m = codec.measurement(node);
    } catch (RuntimeException e) {
      throw new StoreException(file, "is not a valid result: " + e.getMessage(), e);
    }
    if (!locate(m).getFileName().equals(file.getFileName())) {
      throw new StoreException(
          file, "holds the result of job " + m.jobId() + " under another name");
    }
    return m;
  }
}
