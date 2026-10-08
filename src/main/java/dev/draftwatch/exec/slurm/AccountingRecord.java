package dev.draftwatch.exec.slurm;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/** One job as sacct reports it with the format {@code JobIDRaw,State,ExitCode,Start,End}. */
public final class AccountingRecord {
  private final String jobId;
  private final String stateText;
  private final Optional<SlurmState> state;
  private final OptionalInt exitCode;
  private final OptionalInt signal;
  private final Optional<Instant> start;
  private final Optional<Instant> end;

  private AccountingRecord(
      String jobId,
      String stateText,
      OptionalInt exitCode,
      OptionalInt signal,
      Optional<Instant> start,
      Optional<Instant> end) {
    this.jobId = jobId;
    this.stateText = stateText;
    this.state = SlurmState.parse(stateText);
    this.exitCode = exitCode;
    this.signal = signal;
    this.start = start;
    this.end = end;
  }

  static AccountingRecord of(
      String jobId,
      String stateText,
      OptionalInt exitCode,
      OptionalInt signal,
      Optional<Instant> start,
      Optional<Instant> end) {
    return new AccountingRecord(
        Objects.requireNonNull(jobId, "jobId"),
        Objects.requireNonNull(stateText, "stateText"),
        Objects.requireNonNull(exitCode, "exitCode"),
        Objects.requireNonNull(signal, "signal"),
        Objects.requireNonNull(start, "start"),
        Objects.requireNonNull(end, "end"));
  }

  public String jobId() {
    return jobId;
  }

  /** The state exactly as sacct printed it, for example {@code CANCELLED by 1001}. */
  public String stateText() {
    return stateText;
  }

  public Optional<SlurmState> state() {
    return state;
  }

  /** {@code N} of {@code ExitCode N:M}: the batch script's exit code. */
  public OptionalInt exitCode() {
    return exitCode;
  }

  /** {@code M} of {@code ExitCode N:M}: the signal that ended it, 0 if none. */
  public OptionalInt signal() {
    return signal;
  }

  public Optional<Instant> start() {
    return start;
  }

  public Optional<Instant> end() {
    return end;
  }

  /** For messages: {@code COMPLETED, exit code 0:0}. */
  public String describe() {
    return stateText
        + (exitCode.isPresent()
            ? ", exit code " + exitCode.getAsInt() + ":" + signal.orElse(0)
            : "");
  }
}
