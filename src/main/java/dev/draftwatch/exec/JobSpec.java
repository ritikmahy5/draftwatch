package dev.draftwatch.exec;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * What an executor runs for one attempt: a command, the directory to run it in, and the run
 * directory that receives its output files. Executors know nothing about checkpoints or probes.
 *
 * <p>Built with a {@link Builder} because it has several fields and invalid combinations are
 * rejected at {@code build()}.
 */
public final class JobSpec {
  private final String jobId;
  private final int attempt;
  private final List<String> command;
  private final Path workingDir;
  private final Path runDir;

  private JobSpec(Builder b, List<String> command) {
    this.jobId = b.jobId;
    this.attempt = b.attempt;
    this.command = command;
    this.workingDir = b.workingDir;
    this.runDir = b.runDir;
  }

  public static Builder builder() {
    return new Builder();
  }

  public String jobId() {
    return jobId;
  }

  public int attempt() {
    return attempt;
  }

  public List<String> command() {
    return command;
  }

  /** The harness's working directory: the config file's directory. */
  public Path workingDir() {
    return workingDir;
  }

  /** {@code <state>/raw/<job-id>/attempt-<n>}: stdout, stderr, exit code, and the report. */
  public Path runDir() {
    return runDir;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof JobSpec)) {
      return false;
    }
    JobSpec that = (JobSpec) o;
    return jobId.equals(that.jobId)
        && attempt == that.attempt
        && command.equals(that.command)
        && workingDir.equals(that.workingDir)
        && runDir.equals(that.runDir);
  }

  @Override
  public int hashCode() {
    return Objects.hash(jobId, attempt, command, workingDir, runDir);
  }

  @Override
  public String toString() {
    return "JobSpec{" + jobId + " attempt " + attempt + "}";
  }

  /** Builder for {@link JobSpec}; {@link #build()} rejects missing or invalid fields. */
  public static final class Builder {
    private String jobId;
    private int attempt;
    private List<String> command;
    private Path workingDir;
    private Path runDir;

    private Builder() {}

    public Builder jobId(String jobId) {
      this.jobId = jobId;
      return this;
    }

    public Builder attempt(int attempt) {
      this.attempt = attempt;
      return this;
    }

    public Builder command(List<String> command) {
      this.command = command;
      return this;
    }

    public Builder workingDir(Path workingDir) {
      this.workingDir = workingDir;
      return this;
    }

    public Builder runDir(Path runDir) {
      this.runDir = runDir;
      return this;
    }

    /**
     * Builds the spec.
     *
     * @throws NullPointerException naming the first missing field
     * @throws IllegalArgumentException if the attempt is below 1 or the command is empty
     */
    public JobSpec build() {
      Objects.requireNonNull(jobId, "jobId must be set");
      Objects.requireNonNull(command, "command must be set");
      Objects.requireNonNull(workingDir, "workingDir must be set");
      Objects.requireNonNull(runDir, "runDir must be set");
      if (attempt < 1) {
        throw new IllegalArgumentException("attempt must be >= 1, was " + attempt);
      }
      List<String> copy = List.copyOf(command);
      if (copy.isEmpty()) {
        throw new IllegalArgumentException("command must not be empty");
      }
      return new JobSpec(this, copy);
    }
  }
}
