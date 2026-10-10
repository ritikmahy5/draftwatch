package dev.draftwatch.testing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.draftwatch.exec.slurm.CommandResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One job's Slurm output over time, read from {@code fixtures/slurm/<name>.json}. Each observation
 * holds the squeue and sacct results of one poll, with the time it was made. {@code synthetic_}
 * files were written by hand from the documented formats; {@code real_} files were recorded by
 * {@code scripts/record_slurm_fixtures.py}, with the cluster username in paths replaced by
 * {@code user} and the numeric user id in {@code CANCELLED by <uid>} replaced by {@code 1001}.
 */
public final class SlurmScenario {
  /** One poll's recorded results. */
  public static final class Observation {
    private final Instant at;
    private final List<CommandResult> results;

    private Observation(Instant at, List<CommandResult> results) {
      this.at = at;
      this.results = List.copyOf(results);
    }

    public Instant at() {
      return at;
    }

    public List<CommandResult> results() {
      return results;
    }
  }

  private final String name;
  private final String source;
  private final String jobId;
  private final List<Observation> observations;

  private SlurmScenario(
      String name, String source, String jobId, List<Observation> observations) {
    this.name = name;
    this.source = source;
    this.jobId = jobId;
    this.observations = List.copyOf(observations);
  }

  public static Path dir() {
    return FakeHarness.projectDir().resolve("src/test/resources/fixtures/slurm");
  }

  /** Loads {@code fixtures/slurm/<name>.json}. */
  public static SlurmScenario load(String name) {
    Path file = dir().resolve(name + ".json");
    JsonNode root;
    try {
      root = new ObjectMapper().readTree(Files.readAllBytes(file));
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + file, e);
    }
    List<Observation> observations = new ArrayList<>();
    for (JsonNode o : root.get("observations")) {
      List<CommandResult> results = new ArrayList<>();
      for (String command : List.of("squeue", "sacct", "scancel")) {
        if (o.hasNonNull(command)) {
          results.add(result(o.get(command)));
        }
      }
      observations.add(new Observation(Instant.parse(o.get("at").textValue()), results));
    }
    return new SlurmScenario(
        name, root.get("source").textValue(), root.get("job_id").textValue(), observations);
  }

  /** One recorded command: {@code command}, {@code exit_code}, {@code stdout}, {@code stderr}. */
  public static CommandResult result(JsonNode node) {
    List<String> argv = new ArrayList<>();
    node.get("command").forEach(a -> argv.add(a.textValue()));
    return CommandResult.of(
        argv,
        node.get("exit_code").intValue(),
        node.get("stdout").textValue(),
        node.get("stderr").textValue());
  }

  public String name() {
    return name;
  }

  public String source() {
    return source;
  }

  public boolean isRecorded() {
    return name.startsWith("real_");
  }

  /** The Slurm job id, as {@code sbatch --parsable} printed it. */
  public String jobId() {
    return jobId;
  }

  public List<Observation> observations() {
    return observations;
  }
}
