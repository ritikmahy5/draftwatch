package dev.draftwatch.exec.slurm;

import dev.draftwatch.exec.ExecutorException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Slurm commands draftwatch runs and the parsing of their output (Adapter over the
 * {@code sbatch}/{@code squeue}/{@code sacct}/{@code scancel} text interface). The arguments
 * are fixed here, and {@code scripts/record_slurm_fixtures.py} records real output with the
 * same ones. Output that does not match the expected format fails
 * loudly with {@link ExecutorException}.
 */
public final class SlurmCli {
  /** squeue's {@code --format}: job id, state in extended form, actual or expected start. */
  public static final String SQUEUE_FORMAT = "%i|%T|%S";

  /** sacct's {@code --format}. */
  public static final String SACCT_FORMAT = "JobIDRaw,State,ExitCode,Start,End";

  /** {@code SLURM_TIME_FORMAT} for every query: ISO 8601 with the UTC offset. */
  public static final String TIME_FORMAT = "%Y-%m-%dT%H:%M:%S%z";

  /** squeue's message for a job the controller no longer knows ({@code slurm_load_job}). */
  public static final String INVALID_JOB_ID = "Invalid job id specified";

  /** Variables that would change the output format of squeue; removed for every query. */
  static final Set<String> FORMAT_VARIABLES = Set.of("SQUEUE_FORMAT", "SQUEUE_FORMAT2");

  private static final Map<String, String> QUERY_ENVIRONMENT =
      Map.of("SLURM_TIME_FORMAT", TIME_FORMAT);
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern(
      "yyyy-MM-dd'T'HH:mm:ssZ");
  /** What squeue and sacct print for a time that is not set (src/common/parse_time.c). */
  private static final Set<String> NO_TIME = Set.of("N/A", "Unknown", "None", "");
  private static final Pattern EXIT_CODE = Pattern.compile("(\\d+):(\\d+)");

  private final CommandRunner runner;

  public SlurmCli(CommandRunner runner) {
    this.runner = Objects.requireNonNull(runner, "runner");
  }

  // --- the exact argv of each command --------------------------------------------------

  /** {@code sbatch --parsable <options> <script> <args...>}. */
  public static List<String> sbatchArgv(List<String> options, Path script, List<String> args) {
    List<String> argv = new ArrayList<>(List.of("sbatch", "--parsable"));
    argv.addAll(options);
    argv.add(script.toString());
    argv.addAll(args);
    return argv;
  }

  /** The squeue query for one job. */
  public static List<String> squeueArgv(SlurmJobId job) {
    List<String> argv =
        new ArrayList<>(
            List.of("squeue", "--noheader", "--states=all", "--format=" + SQUEUE_FORMAT));
    argv.add("--jobs=" + job.id());
    argv.addAll(job.clusterArgs());
    return argv;
  }

  /** The squeue query for one user's jobs with one name. */
  public static List<String> squeueByNameArgv(String name, String user) {
    return List.of(
        "squeue",
        "--noheader",
        "--states=all",
        "--format=" + SQUEUE_FORMAT,
        "--name=" + name,
        "--user=" + user);
  }

  /** The sacct query for one job. */
  public static List<String> sacctArgv(SlurmJobId job) {
    List<String> argv =
        new ArrayList<>(
            List.of(
                "sacct",
                "--noheader",
                "--parsable2",
                "--allocations",
                "--format=" + SACCT_FORMAT));
    argv.add("--jobs=" + job.id());
    argv.addAll(job.clusterArgs());
    return argv;
  }

  public static List<String> scancelArgv(SlurmJobId job) {
    List<String> argv = new ArrayList<>(List.of("scancel"));
    argv.addAll(job.clusterArgs());
    argv.add(job.id());
    return argv;
  }

  // --- running them ------------------------------------------------------------------------

  /**
   * Submits {@code script} with {@code options} and passes it {@code args}.
   *
   * @param unset environment variables removed for this submission only
   * @throws ExecutorException if sbatch fails or does not print a job id
   */
  public SlurmJobId submit(
      List<String> options, Path script, List<String> args, Set<String> unset) {
    CommandResult r = runner.run(sbatchArgv(options, script, args), Map.of(), unset);
    if (r.exitCode() != 0) {
      throw new ExecutorException("sbatch refused the job: " + r.describe());
    }
    String printed = r.stdout().trim();
    try {
      return SlurmJobId.parse(printed);
    } catch (IllegalArgumentException e) {
      throw new ExecutorException(
          "sbatch --parsable printed '" + printed + "' instead of a job id; the job may have"
              + " been submitted, check squeue",
          e);
    }
  }

  /**
   * The job as squeue lists it.
   *
   * @return empty if squeue does not list it, or no longer knows it
   * @throws ExecutorException for any other failure or unexpected output
   */
  public Optional<QueueEntry> queue(SlurmJobId job) {
    CommandResult r = runner.run(squeueArgv(job), QUERY_ENVIRONMENT, FORMAT_VARIABLES);
    if (r.exitCode() != 0) {
      if (r.stderr().contains(INVALID_JOB_ID)) {
        return Optional.empty();
      }
      throw new ExecutorException("cannot ask squeue about job " + job + ": " + r.describe());
    }
    List<QueueEntry> matching = new ArrayList<>();
    for (QueueEntry e : queueEntries(r)) {
      if (e.jobId().equals(job.id())) {
        matching.add(e);
      }
    }
    if (matching.size() > 1) {
      throw new ExecutorException(
          "squeue listed job " + job + " " + matching.size() + " times: " + r.stdout().trim());
    }
    return matching.stream().findFirst();
  }

  /** Every job of {@code user} named {@code name}, in squeue's order. */
  public List<QueueEntry> queueByName(String name, String user) {
    CommandResult r =
        runner.run(squeueByNameArgv(name, user), QUERY_ENVIRONMENT, FORMAT_VARIABLES);
    if (r.exitCode() != 0) {
      throw new ExecutorException("cannot list jobs named " + name + ": " + r.describe());
    }
    return queueEntries(r);
  }

  /**
   * The job's most recent accounting record.
   *
   * @return empty if sacct has no record of it (yet)
   * @throws ExecutorException if sacct fails or prints something unexpected
   */
  public Optional<AccountingRecord> accounting(SlurmJobId job) {
    CommandResult r = runner.run(sacctArgv(job), QUERY_ENVIRONMENT, FORMAT_VARIABLES);
    if (r.exitCode() != 0) {
      throw new ExecutorException("cannot ask sacct about job " + job + ": " + r.describe());
    }
    List<AccountingRecord> matching = new ArrayList<>();
    for (String line : lines(r.stdout())) {
      String[] f = fields(line, 5, r);
      if (!f[0].equals(job.id())) {
        continue;
      }
      OptionalInt exit = OptionalInt.empty();
      OptionalInt signal = OptionalInt.empty();
      if (!f[2].isEmpty()) {
        Matcher m = EXIT_CODE.matcher(f[2]);
        if (!m.matches()) {
          throw unexpected(r, "ExitCode '" + f[2] + "' is not N:M");
        }
        exit = OptionalInt.of(Integer.parseInt(m.group(1)));
        signal = OptionalInt.of(Integer.parseInt(m.group(2)));
      }
      matching.add(
          AccountingRecord.of(f[0], f[1], exit, signal, time(f[3], r), time(f[4], r)));
    }
    if (matching.size() > 1) {
      throw new ExecutorException(
          "sacct returned " + matching.size() + " records for job " + job + " without"
              + " --duplicates: " + r.stdout().trim());
    }
    return matching.stream().findFirst();
  }

  /**
   * Cancels the job.
   *
   * @throws ExecutorException if scancel fails
   */
  public void cancel(SlurmJobId job) {
    CommandResult r = runner.run(scancelArgv(job), Map.of(), Set.of());
    if (r.exitCode() != 0) {
      throw new ExecutorException("cannot cancel Slurm job " + job + ": " + r.describe());
    }
  }

  // --- parsing -----------------------------------------------------------------------------

  private static List<QueueEntry> queueEntries(CommandResult r) {
    List<QueueEntry> out = new ArrayList<>();
    for (String line : lines(r.stdout())) {
      String[] f = fields(line, 3, r);
      out.add(QueueEntry.of(f[0], f[1], time(f[2], r)));
    }
    return out;
  }

  private static List<String> lines(String text) {
    List<String> out = new ArrayList<>();
    for (String line : text.split("\n", -1)) {
      if (!line.trim().isEmpty()) {
        out.add(line);
      }
    }
    return out;
  }

  private static String[] fields(String line, int count, CommandResult r) {
    String[] f = line.split("\\|", -1);
    if (f.length != count) {
      throw unexpected(r, "line '" + line + "' has " + f.length + " fields, expected " + count);
    }
    for (int i = 0; i < f.length; i++) {
      f[i] = f[i].trim();
    }
    return f;
  }

  private static Optional<Instant> time(String text, CommandResult r) {
    if (NO_TIME.contains(text)) {
      return Optional.empty();
    }
    try {
      return Optional.of(TIME.parse(text, Instant::from));
    } catch (DateTimeParseException e) {
      throw unexpected(r, "time '" + text + "' is not in SLURM_TIME_FORMAT " + TIME_FORMAT);
    }
  }

  private static ExecutorException unexpected(CommandResult r, String what) {
    return new ExecutorException(
        "unexpected output from " + String.join(" ", r.argv()) + ": " + what);
  }
}
