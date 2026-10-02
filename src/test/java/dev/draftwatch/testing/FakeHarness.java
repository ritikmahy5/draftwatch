package dev.draftwatch.testing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Locates {@code scripts/fake_harness.py} and the synthetic fixtures, and builds harness commands
 * that set the fake's environment variables through POSIX {@code env}, so production code needs
 * no test hooks. Everything the fake reports is synthetic (MEASUREMENT_CONTRACT.md).
 */
public final class FakeHarness {
  private FakeHarness() {}

  /** Gradle runs tests with the project directory as the working directory. */
  public static Path projectDir() {
    Path dir = Paths.get("").toAbsolutePath();
    if (!Files.isRegularFile(dir.resolve("scripts/fake_harness.py"))) {
      throw new IllegalStateException("scripts/fake_harness.py not found under " + dir);
    }
    return dir;
  }

  public static Path script() {
    return projectDir().resolve("scripts/fake_harness.py");
  }

  /** A fixture under {@code src/test/resources/fixtures/}, by file name. */
  public static Path fixture(String fileName) {
    Path path = projectDir().resolve("src/test/resources/fixtures").resolve(fileName);
    if (!Files.isRegularFile(path)) {
      throw new IllegalStateException("no fixture " + path);
    }
    return path;
  }

  /** {@code env VAR=value ... python3 fake_harness.py}, for {@code harness.command}. */
  public static List<String> command(Map<String, String> environment) {
    List<String> command = new ArrayList<>();
    command.add("env");
    for (Map.Entry<String, String> e : new TreeMap<>(environment).entrySet()) {
      command.add(e.getKey() + "=" + e.getValue());
    }
    command.add("python3");
    command.add(script().toString());
    return command;
  }

  /** The command for an uncorrupted run on {@code fixtureFile}. */
  public static List<String> command(String fixtureFile) {
    return command(Map.of("DRAFTWATCH_FAKE_FIXTURE", fixture(fixtureFile).toString()));
  }

  /** Writes a prompt file with {@code count} prompts and returns it. */
  public static Path writePrompts(Path dir, int count) {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < count; i++) {
      text.append("{\"prompt\": \"synthetic prompt ").append(i).append("\"}\n");
    }
    Path file = dir.resolve("prompts.jsonl");
    try {
      Files.writeString(file, text.toString());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return file;
  }

  /** Runs {@code command} to completion in {@code workDir}; returns the exit code. */
  public static int run(List<String> command, Path workDir) {
    return run(command, workDir, workDir.resolve("harness-output.log"));
  }

  /** Like {@link #run(List, Path)}, writing stdout and stderr to {@code log}. */
  public static int run(List<String> command, Path workDir, Path log) {
    try {
      Process process =
          new ProcessBuilder(command)
              .directory(workDir.toFile())
              .redirectErrorStream(true)
              .redirectOutput(log.toFile())
              .start();
      return process.waitFor();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
