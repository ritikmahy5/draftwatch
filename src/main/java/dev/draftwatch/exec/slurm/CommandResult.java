package dev.draftwatch.exec.slurm;

import java.util.List;
import java.util.Objects;

/** A finished command: its argv, exit code, and everything it printed. */
public final class CommandResult {
  private final List<String> argv;
  private final int exitCode;
  private final String stdout;
  private final String stderr;

  private CommandResult(List<String> argv, int exitCode, String stdout, String stderr) {
    this.argv = argv;
    this.exitCode = exitCode;
    this.stdout = stdout;
    this.stderr = stderr;
  }

  public static CommandResult of(List<String> argv, int exitCode, String stdout, String stderr) {
    return new CommandResult(
        List.copyOf(argv),
        exitCode,
        Objects.requireNonNull(stdout, "stdout"),
        Objects.requireNonNull(stderr, "stderr"));
  }

  public List<String> argv() {
    return argv;
  }

  public int exitCode() {
    return exitCode;
  }

  public String stdout() {
    return stdout;
  }

  public String stderr() {
    return stderr;
  }

  /** For example {@code squeue ... exited with code 1: slurm_load_jobs error: ...}. */
  public String describe() {
    String err = stderr.trim();
    return String.join(" ", argv) + " exited with code " + exitCode
        + (err.isEmpty() ? "" : ": " + err);
  }
}
