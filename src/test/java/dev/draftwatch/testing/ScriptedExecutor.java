package dev.draftwatch.testing;

import dev.draftwatch.exec.Executor;
import dev.draftwatch.exec.ExecutorException;
import dev.draftwatch.exec.ExecutorStatus;
import dev.draftwatch.exec.JobHandle;
import dev.draftwatch.exec.JobSpec;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * An executor whose statuses are scripted by the test: each {@code status} call returns the next
 * scripted status, and the last one repeats. {@code submit} can be made to fail.
 */
public final class ScriptedExecutor implements Executor {
  private final Deque<ExecutorStatus> script = new ArrayDeque<>();
  private final List<JobSpec> submitted = new ArrayList<>();
  private final List<JobHandle> cancelled = new ArrayList<>();
  private ExecutorStatus last;
  private boolean failSubmit;

  public ScriptedExecutor then(ExecutorStatus status) {
    script.add(status);
    return this;
  }

  public ScriptedExecutor failSubmissions() {
    failSubmit = true;
    return this;
  }

  @Override
  public String name() {
    return "scripted";
  }

  @Override
  public JobHandle submit(JobSpec spec) {
    if (failSubmit) {
      throw new ExecutorException("scripted submission failure");
    }
    submitted.add(spec);
    return JobHandle.of(
        "scripted",
        "s" + submitted.size(),
        spec.runDir(),
        Instant.parse("2026-01-01T00:00:00Z"),
        Optional.empty());
  }

  @Override
  public ExecutorStatus status(JobHandle handle) {
    if (!script.isEmpty()) {
      last = script.poll();
    }
    if (last == null) {
      throw new IllegalStateException("no status scripted");
    }
    return last;
  }

  @Override
  public void cancel(JobHandle handle) {
    cancelled.add(handle);
  }

  public List<JobSpec> submitted() {
    return List.copyOf(submitted);
  }

  public List<JobHandle> cancelled() {
    return List.copyOf(cancelled);
  }
}
