package dev.draftwatch.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.draftwatch.exec.Job;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Jobs as {@code <state>/jobs/<job-id>.json}, each replaced atomically on save. */
public final class FileJobRepository implements JobRepository {
  private final Path dir;
  private final JsonCodec codec;
  private final ObjectMapper json;

  public FileJobRepository(Path stateDir, JsonCodec codec, ObjectMapper json) {
    this.dir = stateDir.resolve("jobs");
    this.codec = Objects.requireNonNull(codec, "codec");
    this.json = Objects.requireNonNull(json, "json");
  }

  private Path file(String id) {
    return dir.resolve(id + ".json");
  }

  @Override
  public void save(Job job) {
    Path file = file(job.id());
    try {
      byte[] content = json.writerWithDefaultPrettyPrinter().writeValueAsBytes(codec.jobJson(job));
      AtomicFiles.write(file, content);
    } catch (IOException e) {
      throw new StoreException(file, "cannot write job: " + e.getMessage(), e);
    }
  }

  @Override
  public Optional<Job> find(String id) {
    Path file = file(id);
    if (!Files.exists(file)) {
      return Optional.empty();
    }
    return Optional.of(read(file));
  }

  @Override
  public List<Job> all() {
    if (!Files.isDirectory(dir)) {
      return List.of();
    }
    List<Path> files;
    try (Stream<Path> list = Files.list(dir)) {
      files =
          list.filter(p -> p.getFileName().toString().endsWith(".json"))
              .filter(p -> !p.getFileName().toString().startsWith("."))
              .sorted()
              .collect(Collectors.toList());
    } catch (IOException e) {
      throw new StoreException(dir, "cannot list jobs: " + e.getMessage(), e);
    }
    List<Job> jobs = new ArrayList<>();
    for (Path file : files) {
      jobs.add(read(file));
    }
    return jobs;
  }

  private Job read(Path file) {
    JsonNode node;
    try {
      node = json.readTree(file.toFile());
    } catch (NoSuchFileException e) {
      throw new StoreException(file, "vanished while reading", e);
    } catch (IOException e) {
      throw new StoreException(file, "is not valid JSON: " + e.getMessage(), e);
    }
    try {
      Job job = codec.job(node);
      String expected = job.id() + ".json";
      if (!file.getFileName().toString().equals(expected)) {
        throw new IllegalArgumentException("holds job " + job.id() + ", expected " + expected);
      }
      return job;
    } catch (RuntimeException e) {
      throw new StoreException(file, "is not a valid job: " + e.getMessage(), e);
    }
  }
}
