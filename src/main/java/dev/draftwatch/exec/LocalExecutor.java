package dev.draftwatch.exec;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Runs attempts as child processes of this machine.
 *
 * <p>The command runs under a {@code /bin/sh} wrapper that, when the command ends, writes its
 * exit status to {@code exit_code} in the run directory (to a temporary file, then renamed), so
 * the outcome survives the draftwatch process that started it. {@link #status} needs only the
 * handle: an exit-code file means EXITED; otherwise a live process with the recorded PID and
 * start time means RUNNING; otherwise the attempt is LOST. The PID check comes first, so a
 * process that exits between the two checks has already written its file. POSIX only.
 */
public final class LocalExecutor implements Executor {
  public static final String NAME = "local";
  public static final String EXIT_FILE = "exit_code";
  public static final String STDOUT_FILE = "stdout.log";
  public static final String STDERR_FILE = "stderr.log";

  static final String EXIT_FILE_VARIABLE = "DRAFTWATCH_EXIT_FILE";

  /** Runs "$@", records its status atomically, and exits with it. */
  static final String WRAPPER =
      "\"$@\"\n"
          + "code=$?\n"
          + "printf '%s\\n' \"$code\" > \"$" + EXIT_FILE_VARIABLE + ".tmp\""
          + " && mv \"$" + EXIT_FILE_VARIABLE + ".tmp\" \"$" + EXIT_FILE_VARIABLE + "\"\n"
          + "exit \"$code\"\n";

  private static final String SHELL = "/bin/sh";

  private final Clock clock;

  public LocalExecutor(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public JobHandle submit(JobSpec spec) {
    Path runDir = spec.runDir();
    try {
      Files.createDirectories(runDir);
    } catch (IOException e) {
      throw new ExecutorException("cannot create run directory " + runDir + ": " + e, e);
    }
    if (Files.exists(runDir.resolve(EXIT_FILE))) {
      throw new ExecutorException(runDir + " already holds a finished attempt");
    }
    List<String> command = new ArrayList<>(List.of(SHELL, "-c", WRAPPER, "draftwatch-job"));
    command.addAll(spec.command());
    ProcessBuilder builder =
        new ProcessBuilder(command)
            .directory(spec.workingDir().toFile())
            .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))
            .redirectOutput(runDir.resolve(STDOUT_FILE).toFile())
            .redirectError(runDir.resolve(STDERR_FILE).toFile());
    builder.environment().put(EXIT_FILE_VARIABLE, runDir.resolve(EXIT_FILE).toString());
    Process process;
    try {
      process = builder.start();
    } catch (IOException e) {
      throw new ExecutorException(
          "cannot start " + SHELL + " in " + spec.workingDir() + ": " + e, e);
    }
    Instant launched = clock.instant();
    return JobHandle.of(
        NAME,
        Long.toString(process.pid()),
        runDir,
        launched,
        process.info().startInstant());
  }

  @Override
  public ExecutorStatus status(JobHandle handle) {
    boolean alive = process(handle).isPresent();
    Path exitFile = handle.runDir().resolve(EXIT_FILE);
    Optional<Instant> started = Optional.of(handle.submittedAt());
    try {
      String text = Files.readString(exitFile).trim();
      Instant written = Files.getLastModifiedTime(exitFile).toInstant();
      // A coarse filesystem clock must not place the end before the launch.
      Instant ended = written.isBefore(handle.submittedAt()) ? handle.submittedAt() : written;
      return ExecutorStatus.exited(Integer.parseInt(text), started, Optional.of(ended));
    } catch (NoSuchFileException e) {
      return alive
          ? ExecutorStatus.running(started)
          : ExecutorStatus.lost(
              "process " + handle.nativeId() + " ended without writing " + exitFile);
    } catch (IOException | NumberFormatException e) {
      return ExecutorStatus.lost("unreadable exit code file " + exitFile + ": " + e);
    }
  }

  @Override
  public void cancel(JobHandle handle) {
    process(handle)
        .ifPresent(
            shell -> {
              shell.descendants().forEach(ProcessHandle::destroy);
              shell.destroy();
            });
  }

  /** The live process for {@code handle}, unless its PID now belongs to another process. */
  private static Optional<ProcessHandle> process(JobHandle handle) {
    long pid;
    try {
      pid = Long.parseLong(handle.nativeId());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("local handle has a non-numeric PID: " + handle, e);
    }
    return ProcessHandle.of(pid)
        .filter(ProcessHandle::isAlive)
        .filter(
            p -> {
              Optional<Instant> recorded = handle.nativeStartTime();
              Optional<Instant> current = p.info().startInstant();
              return recorded.isEmpty() || current.isEmpty() || recorded.equals(current);
            });
  }
}
