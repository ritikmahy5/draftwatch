package dev.draftwatch.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class StateLockTest {
  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
  private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);

  /** A process identity chosen by the test. */
  private static final class Identity implements HostIdentity {
    private final String host;
    private final long pid;
    private final Optional<String> slurm;

    Identity(String host, long pid, Optional<String> slurm) {
      this.host = host;
      this.pid = pid;
      this.slurm = slurm;
    }

    @Override
    public String hostname() {
      return host;
    }

    @Override
    public long pid() {
      return pid;
    }

    @Override
    public Optional<Instant> processStart() {
      return Optional.of(T0.minusSeconds(pid));
    }

    @Override
    public Optional<String> slurmJobId() {
      return slurm;
    }
  }

  /** Liveness chosen by the test: the listed PIDs are alive. */
  private static final class Processes implements ProcessTable {
    final Set<Long> alive = new HashSet<>();

    @Override
    public boolean isAlive(long pid, Optional<Instant> start) {
      return alive.contains(pid);
    }
  }

  private final Processes processes = new Processes();
  private Path state;

  @Before
  public void setUp() {
    state = tmp.getRoot().toPath().resolve("state");
  }

  private StateLock lockFor(String host, long pid) {
    return new StateLock(new Identity(host, pid, Optional.empty()), processes, CLOCK);
  }

  private static void assertRefused(StateLock lock, Path state, String fragment) {
    try {
      lock.acquire(state, "submit");
      fail("expected StateLockException");
    } catch (StateLockException e) {
      assertTrue(e.getMessage(), e.getMessage().contains(fragment));
    }
  }

  @Test
  public void acquireWritesHolderAndCloseReleases() {
    StateLock lock = lockFor("node1", 100);
    StateLock.Held held = lock.acquire(state, "submit");
    LockHolder holder = lock.holder(state).orElseThrow();
    assertEquals("node1", holder.host());
    assertEquals(100, holder.pid());
    assertEquals(Optional.of(T0.minusSeconds(100)), holder.pidStart());
    assertEquals(T0, holder.acquiredAt());
    assertEquals("submit", holder.command());
    held.close();
    assertEquals(Optional.empty(), lock.holder(state));
  }

  @Test
  public void liveHolderOnThisHostIsNotTakenOver() {
    processes.alive.add(100L);
    lockFor("node1", 100).acquire(state, "watch");
    assertRefused(lockFor("node1", 200), state, "which is still running");
  }

  // --- ROADMAP M2 "done when" ----------------------------------------------------------------

  @Test
  public void lockHeldByDeadPidOnThisHostIsTakenOver() throws IOException {
    lockFor("node1", 100).acquire(state, "watch"); // never released; PID 100 is not alive
    StateLock.Held held = lockFor("node1", 200).acquire(state, "submit");
    assertEquals(200, lockFor("node1", 300).holder(state).orElseThrow().pid());
    try (Stream<Path> files = Files.list(state)) {
      assertEquals("the stale lock is removed", 1, files.count());
    }
    held.close();
  }

  @Test
  public void lockFromAnotherHostIsNotTakenOver() {
    lockFor("node2", 100).acquire(state, "watch"); // PID 100 is "dead", but on another host
    assertRefused(lockFor("node1", 200), state, "on another host; it is never taken over");
    assertEquals("node2", lockFor("node1", 200).holder(state).orElseThrow().host());
  }

  // --- other holders ---------------------------------------------------------------------------

  @Test
  public void lockHeldFromASlurmJobIsNotTakenOverBeforeM5() {
    new StateLock(new Identity("node1", 100, Optional.of("4242")), processes, CLOCK)
        .acquire(state, "watch");
    assertRefused(lockFor("node1", 200), state, "Slurm job 4242");
  }

  @Test
  public void unreadableLockIsNotTakenOver() throws IOException {
    Files.createDirectories(state);
    Files.writeString(state.resolve(StateLock.LOCK_FILE), "");
    assertRefused(lockFor("node1", 200), state, "exists but is unreadable");
  }

  @Test
  public void closeDoesNotDeleteALockThatIsNoLongerOurs() throws IOException {
    StateLock.Held stale = lockFor("node1", 100).acquire(state, "watch");
    StateLock.Held current = lockFor("node1", 200).acquire(state, "submit"); // took over
    stale.close();
    assertEquals(200, lockFor("node1", 1).holder(state).orElseThrow().pid());
    current.close();
    assertFalse(Files.exists(state.resolve(StateLock.LOCK_FILE)));
  }

  @Test
  public void racingTakeoverRestoresTheWinnersLockAndBacksOff() throws IOException {
    lockFor("node1", 100).acquire(state, "watch"); // dead holder
    Path lockFile = state.resolve(StateLock.LOCK_FILE);
    String[] winner = new String[1];
    ProcessTable raceDuringCheck =
        (pid, start) -> {
          // Between our read of the dead holder and our rename, PID 300 takes over first.
          try {
            Files.delete(lockFile);
            new StateLock(new Identity("node1", 300, Optional.empty()), processes, CLOCK)
                .acquire(state, "watch");
            winner[0] = Files.readString(lockFile);
          } catch (IOException e) {
            throw new IllegalStateException(e);
          }
          return false;
        };
    StateLock loser =
        new StateLock(new Identity("node1", 200, Optional.empty()), raceDuringCheck, CLOCK);
    try {
      loser.acquire(state, "submit");
      fail("expected StateLockException");
    } catch (StateLockException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("taken by another process"));
    }
    assertEquals("the winner's lock is back in place", winner[0], Files.readString(lockFile));
    try (Stream<Path> files = Files.list(state)) {
      assertEquals(1, files.count());
    }
  }

  @Test
  public void lockFileIsJsonWithEveryHolderField() throws IOException {
    lockFor("node1", 100).acquire(state, "submit");
    String text = Files.readString(state.resolve(StateLock.LOCK_FILE));
    assertEquals(
        Set.of("host", "pid", "pid_start", "slurm_job_id", "acquired_at", "command"),
        fieldNames(text));
  }

  private static Set<String> fieldNames(String json) throws IOException {
    Set<String> names = new HashSet<>();
    new ObjectMapper().readTree(json).fieldNames().forEachRemaining(names::add);
    return names;
  }

  @Test
  public void systemProcessTableSeesThisProcessAndNotAFinishedOne() throws Exception {
    SystemProcessTable table = new SystemProcessTable();
    ProcessHandle me = ProcessHandle.current();
    assertTrue(table.isAlive(me.pid(), me.info().startInstant()));
    Process finished = new ProcessBuilder("true").start();
    long pid = finished.pid();
    finished.waitFor();
    assertFalse(table.isAlive(pid, Optional.empty()));
    assertFalse(
        "same PID, other start time",
        table.isAlive(me.pid(), Optional.of(Instant.parse("2000-01-01T00:00:00Z"))));
  }
}
