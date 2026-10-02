package dev.draftwatch.testing;

import dev.draftwatch.exec.Executor;
import dev.draftwatch.exec.ExecutorStatus;
import dev.draftwatch.exec.JobHandle;
import dev.draftwatch.exec.JobSpec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An executor for end-to-end tests: {@code submit} runs the command to completion in the spec's
 * working directory, synchronously, and {@code status} then reports how it exited. With the fake
 * harness this exercises the whole pipeline deterministically, without background processes.
 */
public final class FakeExecutor implements Executor {
  public static final String NAME = "fake";

  private final Clock clock;
  private final Map<String, Integer> exitCodes = new HashMap<>();
  private final Map<String, Instant[]> times = new HashMap<>();
  private final List<JobSpec> submitted = new ArrayList<>();

  public FakeExecutor(Clock clock) {
    this.clock = clock;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public JobHandle submit(JobSpec spec) {
    try {
      Files.createDirectories(spec.runDir());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    String id = "fake-" + (submitted.size() + 1);
    Instant start = clock.instant();
    int code =
        FakeHarness.run(spec.command(), spec.workingDir(), spec.runDir().resolve("output.log"));
    exitCodes.put(id, code);
    times.put(id, new Instant[] {start, clock.instant()});
    submitted.add(spec);
    return JobHandle.of(NAME, id, spec.runDir(), start, Optional.empty());
  }

  @Override
  public ExecutorStatus status(JobHandle handle) {
    Instant[] t = times.get(handle.nativeId());
    return ExecutorStatus.exited(
        exitCodes.get(handle.nativeId()), Optional.of(t[0]), Optional.of(t[1]));
  }

  @Override
  public void cancel(JobHandle handle) {}

  /** Every spec submitted so far, in order. */
  public List<JobSpec> submitted() {
    return List.copyOf(submitted);
  }
}
