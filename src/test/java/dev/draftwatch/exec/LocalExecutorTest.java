package dev.draftwatch.exec;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Real child processes; nothing here needs a GPU, Slurm, or network access. */
public class LocalExecutorTest {
  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private static final Duration TIMEOUT = Duration.ofSeconds(20);

  private final LocalExecutor executor = new LocalExecutor(Clock.systemUTC());
  private Path work;
  private JobHandle started;

  @Before
  public void setUp() {
    work = tmp.getRoot().toPath();
  }

  @After
  public void killLeftovers() {
    if (started != null) {
      ProcessHandle.of(Long.parseLong(started.nativeId()))
          .ifPresent(
              p -> {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
              });
    }
  }

  private JobHandle submit(String... command) {
    started =
        executor.submit(
            JobSpec.builder()
                .jobId("j1")
                .attempt(1)
                .command(List.of(command))
                .workingDir(work)
                .runDir(work.resolve("raw/j1/attempt-1"))
                .build());
    return started;
  }

  private ExecutorStatus awaitNot(JobHandle handle, ExecutorStatus.Kind kind) {
    Instant deadline = Instant.now().plus(TIMEOUT);
    while (Instant.now().isBefore(deadline)) {
      ExecutorStatus status = executor.status(handle);
      if (status.kind() != kind) {
        return status;
      }
      sleep(20);
    }
    fail("still " + kind + " after " + TIMEOUT);
    return null;
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  @Test
  public void recordsExitCodeAndOutputInTheRunDirectory() throws IOException {
    JobHandle handle = submit("sh", "-c", "echo to-out; echo to-err >&2; exit 3");
    ExecutorStatus status = awaitNot(handle, ExecutorStatus.Kind.RUNNING);
    assertEquals(ExecutorStatus.Kind.EXITED, status.kind());
    assertEquals(3, status.exitCode().getAsInt());
    Path run = handle.runDir();
    assertEquals("to-out\n", Files.readString(run.resolve(LocalExecutor.STDOUT_FILE)));
    assertEquals("to-err\n", Files.readString(run.resolve(LocalExecutor.STDERR_FILE)));
    assertEquals("3\n", Files.readString(run.resolve(LocalExecutor.EXIT_FILE)));
    assertEquals(Optional.of(handle.submittedAt()), status.startedAt());
    assertFalse(status.endedAt().get().isBefore(handle.submittedAt()));
  }

  @Test
  public void runsInTheWorkingDirectory() throws IOException {
    JobHandle handle = submit("sh", "-c", "pwd -P > where.txt");
    assertEquals(0, awaitNot(handle, ExecutorStatus.Kind.RUNNING).exitCode().getAsInt());
    assertEquals(
        work.toRealPath().toString(), Files.readString(work.resolve("where.txt")).trim());
  }

  @Test
  public void argumentsArePassedVerbatim() throws IOException {
    JobHandle handle =
        submit("sh", "-c", "printf '%s|' \"$@\" > args.txt", "sh", "a b", "{\"k\": 1}", "$HOME");
    awaitNot(handle, ExecutorStatus.Kind.RUNNING);
    assertEquals("a b|{\"k\": 1}|$HOME|", Files.readString(work.resolve("args.txt")));
  }

  @Test
  public void statusIsRecoverableByAnotherExecutorInstance() {
    JobHandle handle = submit("sh", "-c", "exit 5");
    awaitNot(handle, ExecutorStatus.Kind.RUNNING);
    ExecutorStatus fromNewInstance = new LocalExecutor(Clock.systemUTC()).status(handle);
    assertEquals(5, fromNewInstance.exitCode().getAsInt());
  }

  @Test
  public void longRunningCommandIsRunningUntilCancelled() {
    JobHandle handle = submit("sleep", "30");
    assertEquals(ExecutorStatus.Kind.RUNNING, executor.status(handle).kind());
    executor.cancel(handle);
    ExecutorStatus after = awaitNot(handle, ExecutorStatus.Kind.RUNNING);
    assertTrue(after.toString(), after.kind() != ExecutorStatus.Kind.RUNNING);
  }

  @Test
  public void killedWrapperIsLost() {
    JobHandle handle = submit("sleep", "30");
    ProcessHandle shell = ProcessHandle.of(Long.parseLong(handle.nativeId())).orElseThrow();
    List<ProcessHandle> children = List.of();
    Instant deadline = Instant.now().plus(TIMEOUT);
    while (children.isEmpty() && Instant.now().isBefore(deadline)) {
      children = shell.descendants().collect(Collectors.toList());
      sleep(10);
    }
    assertFalse("the wrapper never started sleep", children.isEmpty());
    shell.destroyForcibly();
    children.forEach(ProcessHandle::destroyForcibly);
    ExecutorStatus status = awaitNot(handle, ExecutorStatus.Kind.RUNNING);
    assertEquals(ExecutorStatus.Kind.LOST, status.kind());
    assertTrue(status.detail(), status.detail().contains("ended without writing"));
  }

  @Test
  public void reusedPidWithAnotherStartTimeIsNotThisJob() {
    JobHandle real = submit("sleep", "30");
    JobHandle impostor =
        JobHandle.of(
            LocalExecutor.NAME,
            real.nativeId(),
            real.runDir(),
            real.submittedAt(),
            Optional.of(Instant.parse("2000-01-01T00:00:00Z")));
    assertEquals(ExecutorStatus.Kind.LOST, executor.status(impostor).kind());
  }

  @Test
  public void runDirectoryWithAFinishedAttemptIsNotReused() throws IOException {
    Path run = Files.createDirectories(work.resolve("raw/j1/attempt-1"));
    Files.writeString(run.resolve(LocalExecutor.EXIT_FILE), "0\n");
    try {
      submit("true");
      fail("expected ExecutorException");
    } catch (ExecutorException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("already holds a finished attempt"));
    }
  }

  @Test
  public void missingWorkingDirectoryFailsSubmission() {
    work = tmp.getRoot().toPath().resolve("does-not-exist");
    try {
      started = null;
      executor.submit(
          JobSpec.builder()
              .jobId("j1")
              .attempt(1)
              .command(List.of("true"))
              .workingDir(work)
              .runDir(tmp.getRoot().toPath().resolve("raw/j1/attempt-1"))
              .build());
      fail("expected ExecutorException");
    } catch (ExecutorException e) {
      assertTrue(e.getMessage(), e.getMessage().startsWith("cannot start /bin/sh"));
    }
  }
}
