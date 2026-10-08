package dev.draftwatch.exec;

import dev.draftwatch.domain.Checkpoint;
import dev.draftwatch.domain.ResolvedProbe;
import dev.draftwatch.harness.ExpectedReport;
import dev.draftwatch.harness.HarnessInvocation;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * What a job measures: one resolved probe on one checkpoint, run by a given harness command and
 * executor. Fixed for the life of the job; each attempt gets its own {@link JobSpec} and run
 * directory {@code <rawDir>/attempt-<n>}.
 */
public final class MeasurementSpec {
  public static final String REPORT_FILE = "report.json";

  private final Checkpoint checkpoint;
  private final ResolvedProbe probe;
  private final List<String> harnessCommand;
  private final Path workingDir;
  private final Path rawDir;
  private final String executor;

  private MeasurementSpec(
      Checkpoint checkpoint,
      ResolvedProbe probe,
      List<String> harnessCommand,
      Path workingDir,
      Path rawDir,
      String executor) {
    this.checkpoint = checkpoint;
    this.probe = probe;
    this.harnessCommand = harnessCommand;
    this.workingDir = workingDir;
    this.rawDir = rawDir;
    this.executor = executor;
  }

  /**
   * Creates a measurement spec.
   *
   * @param rawDir {@code <state>/raw/<job-id>}
   * @param executor the executor type name recorded in provenance, for example {@code local}
   */
  public static MeasurementSpec of(
      Checkpoint checkpoint,
      ResolvedProbe probe,
      List<String> harnessCommand,
      Path workingDir,
      Path rawDir,
      String executor) {
    List<String> command = List.copyOf(harnessCommand);
    if (command.isEmpty()) {
      throw new IllegalArgumentException("harness command must not be empty");
    }
    if (executor == null || executor.isEmpty()) {
      throw new IllegalArgumentException("executor must not be empty");
    }
    return new MeasurementSpec(
        Objects.requireNonNull(checkpoint, "checkpoint"),
        Objects.requireNonNull(probe, "probe"),
        command,
        Objects.requireNonNull(workingDir, "workingDir"),
        Objects.requireNonNull(rawDir, "rawDir"),
        executor);
  }

  public Checkpoint checkpoint() {
    return checkpoint;
  }

  public ResolvedProbe probe() {
    return probe;
  }

  public List<String> harnessCommand() {
    return harnessCommand;
  }

  public Path workingDir() {
    return workingDir;
  }

  public Path rawDir() {
    return rawDir;
  }

  public String executor() {
    return executor;
  }

  public Path runDir(int attempt) {
    if (attempt < 1) {
      throw new IllegalArgumentException("attempt must be >= 1, was " + attempt);
    }
    return rawDir.resolve("attempt-" + attempt);
  }

  public Path reportPath(int attempt) {
    return runDir(attempt).resolve(REPORT_FILE);
  }

  /** The exact harness invocation for {@code attempt}. */
  public HarnessInvocation invocation(int attempt) {
    return HarnessInvocation.builder()
        .harnessCommand(harnessCommand)
        .checkpoint(checkpoint)
        .probe(probe.probe())
        .out(reportPath(attempt))
        .build();
  }

  /** What the executor runs for {@code attempt} of job {@code jobId}. */
  public JobSpec jobSpec(String jobId, int attempt) {
    return JobSpec.builder()
        .jobId(jobId)
        .attempt(attempt)
        .command(invocation(attempt).command())
        .workingDir(workingDir)
        .runDir(runDir(attempt))
        .build();
  }

  /** What the report must echo for this measurement. */
  public ExpectedReport expectedReport() {
    return ExpectedReport.of(probe, checkpoint.type());
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof MeasurementSpec)) {
      return false;
    }
    MeasurementSpec that = (MeasurementSpec) o;
    return checkpoint.equals(that.checkpoint)
        && probe.equals(that.probe)
        && harnessCommand.equals(that.harnessCommand)
        && workingDir.equals(that.workingDir)
        && rawDir.equals(that.rawDir)
        && executor.equals(that.executor);
  }

  @Override
  public int hashCode() {
    return Objects.hash(checkpoint, probe, harnessCommand, workingDir, rawDir, executor);
  }

  @Override
  public String toString() {
    return "MeasurementSpec{" + probe.probe().id() + " on " + checkpoint + "}";
  }
}
