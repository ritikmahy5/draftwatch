package dev.draftwatch.exec.slurm;

import dev.draftwatch.exec.ExecutorException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs one command to completion and captures its output (Strategy: the real implementation
 * starts a process; tests replay recorded output, DECISIONS.md D64).
 */
public interface CommandRunner {
  /**
   * Runs {@code argv} with this process's environment, plus {@code set}, minus {@code unset}.
   *
   * @throws ExecutorException if the command cannot be started or does not finish in time
   */
  CommandResult run(List<String> argv, Map<String, String> set, Set<String> unset);
}
