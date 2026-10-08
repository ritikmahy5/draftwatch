package dev.draftwatch.testing;

import dev.draftwatch.exec.slurm.CommandResult;
import dev.draftwatch.exec.slurm.CommandRunner;
import dev.draftwatch.exec.slurm.SlurmCli;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * A simulated Slurm cluster for end-to-end tests, answering the commands of {@link
 * dev.draftwatch.exec.slurm.SlurmCli} in their documented formats. It is a simulation, not a
 * recording: real output is pinned by the fixtures under {@code fixtures/slurm/}. Submitted jobs
 * stay PENDING until {@link #runQueued} runs their batch scripts with {@code /bin/sh}, one at a
 * time, in this process's environment. A finished job leaves squeue at once and stays in sacct.
 */
public final class FakeSlurm implements CommandRunner {
  private static final DateTimeFormatter TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ").withZone(ZoneOffset.UTC);

  /** One submitted job. */
  public static final class Job {
    private final String id;
    private final List<String> options;
    private final Path script;
    private final List<String> args;
    private final Set<String> unset;
    private String state = "PENDING";
    private int exitCode;
    private Instant start;
    private Instant end;

    private Job(
        String id, List<String> options, Path script, List<String> args, Set<String> unset) {
      this.id = id;
      this.options = List.copyOf(options);
      this.script = script;
      this.args = List.copyOf(args);
      this.unset = Set.copyOf(unset);
    }

    public String id() {
      return id;
    }

    /** Every option before the script, as sbatch received it. */
    public List<String> options() {
      return options;
    }

    public Path script() {
      return script;
    }

    /** The environment variables removed for this submission. */
    public Set<String> unset() {
      return unset;
    }

    public String state() {
      return state;
    }

    /** The value of {@code --<name>=}, if given. */
    public String option(String name) {
      for (String o : options) {
        if (o.startsWith("--" + name + "=")) {
          return o.substring(name.length() + 3);
        }
      }
      return null;
    }
  }

  private final Map<String, Job> jobs = new LinkedHashMap<>();
  private final List<List<String>> commands = new ArrayList<>();
  private int nextId = 9001;
  private boolean controllerDown;

  /** Makes squeue and sacct fail as with an unreachable controller, until set back. */
  public FakeSlurm controllerDown(boolean down) {
    controllerDown = down;
    return this;
  }

  /** Every submitted job, in submission order. */
  public List<Job> jobs() {
    return List.copyOf(jobs.values());
  }

  /** Every command run, oldest first. */
  public List<List<String>> commands() {
    return List.copyOf(commands);
  }

  /** Runs every PENDING job to completion, in submission order. */
  public void runQueued() {
    for (Job job : jobs()) {
      if (job.state.equals("PENDING")) {
        run(job);
      }
    }
  }

  private void run(Job job) {
    job.state = "RUNNING";
    job.start = Instant.now();
    List<String> command = new ArrayList<>(List.of("/bin/sh", job.script.toString()));
    command.addAll(job.args);
    ProcessBuilder builder =
        new ProcessBuilder(command)
            .directory(new File(job.option("chdir")))
            .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))
            .redirectOutput(ProcessBuilder.Redirect.appendTo(new File(job.option("output"))))
            .redirectError(ProcessBuilder.Redirect.appendTo(new File(job.option("error"))));
    builder.environment().put("SLURM_JOB_ID", job.id);
    try {
      Process p = builder.start();
      if (!p.waitFor(120, TimeUnit.SECONDS)) {
        p.destroyForcibly();
        throw new IllegalStateException("fake Slurm job " + job.id + " did not finish");
      }
      job.exitCode = p.exitValue();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
    job.end = Instant.now();
    job.state = job.exitCode == 0 ? "COMPLETED" : "FAILED";
  }

  @Override
  public CommandResult run(List<String> argv, Map<String, String> set, Set<String> unset) {
    commands.add(List.copyOf(argv));
    switch (argv.get(0)) {
      case "sbatch":
        return sbatch(argv, unset);
      case "squeue":
        return squeue(argv);
      case "sacct":
        return sacct(argv);
      case "scancel":
        Job job = jobs.get(argv.get(argv.size() - 1));
        if (job != null) {
          job.state = "CANCELLED";
        }
        return CommandResult.of(argv, 0, "", "");
      default:
        throw new AssertionError("FakeSlurm does not run " + argv);
    }
  }

  private CommandResult sbatch(List<String> argv, Set<String> unset) {
    int i = 2; // after "sbatch --parsable"
    List<String> options = new ArrayList<>();
    while (argv.get(i).startsWith("--")) {
      options.add(argv.get(i++));
    }
    Path script = Paths.get(argv.get(i));
    String id = Integer.toString(nextId++);
    jobs.put(id, new Job(id, options, script, argv.subList(i + 1, argv.size()), unset));
    return CommandResult.of(argv, 0, id + "\n", "");
  }

  private CommandResult squeue(List<String> argv) {
    if (controllerDown) {
      return CommandResult.of(
          argv, 1, "",
          "slurm_load_jobs error: Unable to contact slurm controller (connect failure)\n");
    }
    StringBuilder out = new StringBuilder();
    String wanted = value(argv, "--jobs=");
    if (wanted != null) {
      Job job = jobs.get(wanted);
      if (job == null || !inQueue(job)) {
        return CommandResult.of(argv, 1, "", "slurm_load_jobs error: " + SlurmCli.INVALID_JOB_ID
            + "\n");
      }
      out.append(line(job));
    } else {
      String name = value(argv, "--name=");
      for (Job job : jobs.values()) {
        if (inQueue(job) && name.equals(job.option("job-name"))) {
          out.append(line(job));
        }
      }
    }
    return CommandResult.of(argv, 0, out.toString(), "");
  }

  private CommandResult sacct(List<String> argv) {
    if (controllerDown) {
      return CommandResult.of(
          argv, 1, "", "sacct: error: Problem talking to the database: Connection refused\n");
    }
    Job job = jobs.get(value(argv, "--jobs="));
    if (job == null) {
      return CommandResult.of(argv, 0, "", "");
    }
    return CommandResult.of(
        argv,
        0,
        job.id + "|" + job.state + "|" + job.exitCode + ":0|" + time(job.start) + "|"
            + time(job.end) + "\n",
        "");
  }

  private static boolean inQueue(Job job) {
    return job.state.equals("PENDING") || job.state.equals("RUNNING");
  }

  private static String line(Job job) {
    return job.id + "|" + job.state + "|" + (job.start == null ? "N/A" : time(job.start)) + "\n";
  }

  private static String time(Instant t) {
    return t == null ? "Unknown" : TIME.format(t);
  }

  private static String value(List<String> argv, String prefix) {
    for (String a : argv) {
      if (a.startsWith(prefix)) {
        return a.substring(prefix.length());
      }
    }
    return null;
  }
}
