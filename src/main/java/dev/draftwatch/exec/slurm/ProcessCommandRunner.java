package dev.draftwatch.exec.slurm;

import dev.draftwatch.exec.ExecutorException;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Runs commands as child processes, with stdin from {@code /dev/null} and output captured in
 * temporary files, which are deleted afterwards. A command that runs past the timeout is killed.
 */
public final class ProcessCommandRunner implements CommandRunner {
  /** How long a Slurm command may take before it is killed. */
  public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);

  private final Duration timeout;

  public ProcessCommandRunner(Duration timeout) {
    this.timeout = Objects.requireNonNull(timeout, "timeout");
  }

  @Override
  public CommandResult run(List<String> argv, Map<String, String> set, Set<String> unset) {
    Path out = null;
    Path err = null;
    try {
      out = Files.createTempFile("draftwatch-cmd-", ".out");
      err = Files.createTempFile("draftwatch-cmd-", ".err");
      ProcessBuilder builder =
          new ProcessBuilder(argv)
              .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))
              .redirectOutput(out.toFile())
              .redirectError(err.toFile());
      Map<String, String> env = builder.environment();
      unset.forEach(env::remove);
      env.putAll(set);
      Process process = builder.start();
      if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        throw new ExecutorException(
            String.join(" ", argv) + " did not finish within " + timeout.getSeconds()
                + " s and was killed");
      }
      return CommandResult.of(
          argv,
          process.exitValue(),
          Files.readString(out, StandardCharsets.UTF_8),
          Files.readString(err, StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new ExecutorException("cannot run " + String.join(" ", argv) + ": " + e, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ExecutorException("interrupted while running " + argv.get(0), e);
    } finally {
      deleteQuietly(out);
      deleteQuietly(err);
    }
  }

  private static void deleteQuietly(Path file) {
    if (file == null) {
      return;
    }
    try {
      Files.deleteIfExists(file);
    } catch (IOException e) {
      // A leftover temporary file is harmless.
    }
  }
}
