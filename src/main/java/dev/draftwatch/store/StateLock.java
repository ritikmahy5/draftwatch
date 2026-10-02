package dev.draftwatch.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * The single-writer lock {@code <state>/lock} (ARCHITECTURE.md, "StateLock"; DECISIONS.md D34).
 *
 * <p>The lock is acquired by creating the file exclusively; exclusive create is used instead of
 * {@code flock}, which is unreliable on network filesystems. A lock may be taken over only when
 * its holder is provably gone:
 *
 * <ul>
 *   <li>it ran inside a Slurm job that squeue no longer lists alive, on any host (D62);
 *   <li>it ran outside Slurm on this host, and its PID is not alive (or is now a different
 *       process, by start time).
 * </ul>
 *
 * A holder outside Slurm on another host is never taken over, and neither is a Slurm holder when
 * squeue cannot be asked.
 *
 * <p>Takeover renames the stale lock aside, then checks that the renamed file is the one it
 * inspected; if another process replaced it in between, the fresh lock is put back and
 * acquisition fails. Two processes racing to take over one dead lock therefore cannot both win.
 */
public final class StateLock {
  public static final String LOCK_FILE = "lock";
  private static final int MAX_ROUNDS = 3;

  private final HostIdentity self;
  private final ProcessTable processes;
  private final SlurmJobTable slurmJobs;
  private final Clock clock;
  private final ObjectMapper json = new ObjectMapper();

  public StateLock(
      HostIdentity self, ProcessTable processes, SlurmJobTable slurmJobs, Clock clock) {
    this.self = Objects.requireNonNull(self, "self");
    this.processes = Objects.requireNonNull(processes, "processes");
    this.slurmJobs = Objects.requireNonNull(slurmJobs, "slurmJobs");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** A held lock; closing it releases the lock if it is still ours. */
  public final class Held implements AutoCloseable {
    private final Path file;
    private final byte[] content;
    private boolean released;

    private Held(Path file, byte[] content) {
      this.file = file;
      this.content = content;
    }

    @Override
    public void close() {
      if (released) {
        return;
      }
      released = true;
      try {
        if (Arrays.equals(Files.readAllBytes(file), content)) {
          Files.delete(file);
        }
      } catch (NoSuchFileException e) {
        // Already gone; nothing to release.
      } catch (IOException e) {
        throw new StateLockException("cannot release " + file + ": " + e.getMessage(), e);
      }
    }
  }

  /**
   * Acquires the lock of {@code stateDir} for {@code command}, creating the directory if needed.
   *
   * @throws StateLockException naming the holder if the lock is held by a process that is
   *     alive, on another host outside Slurm, in a Slurm job squeue lists alive or cannot be
   *     asked about, or that cannot be identified
   */
  public Held acquire(Path stateDir, String command) {
    Path lock = stateDir.resolve(LOCK_FILE);
    LockHolder me =
        LockHolder.of(
            self.hostname(),
            self.pid(),
            self.processStart(),
            self.slurmJobId(),
            clock.instant(),
            command);
    byte[] content = bytes(me);
    try {
      Files.createDirectories(stateDir);
    } catch (IOException e) {
      throw new StateLockException("cannot create " + stateDir + ": " + e.getMessage(), e);
    }
    for (int round = 0; round < MAX_ROUNDS; round++) {
      try {
        Files.write(lock, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        return new Held(lock, content);
      } catch (FileAlreadyExistsException e) {
        // Held by someone; decide below whether they are provably gone.
      } catch (IOException e) {
        throw new StateLockException("cannot create " + lock + ": " + e.getMessage(), e);
      }
      byte[] seen;
      try {
        seen = Files.readAllBytes(lock);
      } catch (NoSuchFileException e) {
        continue; // released between our attempts
      } catch (IOException e) {
        throw new StateLockException("cannot read " + lock + ": " + e.getMessage(), e);
      }
      LockHolder holder = parse(lock, seen);
      requireGone(lock, holder);
      takeOver(stateDir, lock, seen, holder);
    }
    throw new StateLockException(
        lock + " changed hands " + MAX_ROUNDS + " times while acquiring it; try again");
  }

  /** The current holder of {@code stateDir}'s lock, if any (for {@code draftwatch status}). */
  public Optional<LockHolder> holder(Path stateDir) {
    Path lock = stateDir.resolve(LOCK_FILE);
    try {
      return Optional.of(parse(lock, Files.readAllBytes(lock)));
    } catch (NoSuchFileException e) {
      return Optional.empty();
    } catch (IOException e) {
      throw new StateLockException("cannot read " + lock + ": " + e.getMessage(), e);
    }
  }

  private void requireGone(Path lock, LockHolder holder) {
    if (holder.slurmJobId().isPresent()) {
      String job = holder.slurmJobId().get();
      boolean alive;
      try {
        alive = slurmJobs.isAlive(job);
      } catch (RuntimeException e) {
        throw new StateLockException(
            lock + " is held by " + holder + "; cannot tell whether Slurm job " + job
                + " has ended, so it is not taken over: " + e.getMessage(),
            e);
      }
      if (alive) {
        throw new StateLockException(
            lock + " is held by " + holder + ", which is still running (squeue lists Slurm job "
                + job + ")");
      }
      return; // squeue no longer lists the job alive: its process is gone, whatever the host
    }
    if (!holder.host().equals(self.hostname())) {
      throw new StateLockException(
          lock + " is held by " + holder + " on another host; it is never taken over"
              + " automatically. Remove the file if no draftwatch process runs there");
    }
    if (processes.isAlive(holder.pid(), holder.pidStart())) {
      throw new StateLockException(lock + " is held by " + holder + ", which is still running");
    }
  }

  private void takeOver(Path stateDir, Path lock, byte[] seen, LockHolder holder) {
    Path aside = stateDir.resolve(LOCK_FILE + ".stale-" + self.pid() + "-" + System.nanoTime());
    try {
      Files.move(lock, aside, StandardCopyOption.ATOMIC_MOVE);
    } catch (NoSuchFileException e) {
      return; // someone else moved it first; the next round decides again
    } catch (IOException e) {
      throw new StateLockException("cannot move stale " + lock + ": " + e.getMessage(), e);
    }
    try {
      if (Arrays.equals(Files.readAllBytes(aside), seen)) {
        Files.delete(aside);
        return; // the dead holder's lock is gone; the next round creates ours
      }
      // Another process replaced the stale lock between our read and our move: restore theirs.
      Files.move(aside, lock);
    } catch (IOException e) {
      throw new StateLockException(
          "lock contention while taking over from " + holder + "; check " + stateDir
              + " for " + aside.getFileName() + " and " + LOCK_FILE + ": " + e.getMessage(),
          e);
    }
    throw new StateLockException(lock + " was taken by another process during takeover");
  }

  private LockHolder parse(Path lock, byte[] content) {
    try {
      return LockHolder.fromJson(json.readTree(content));
    } catch (IOException | RuntimeException e) {
      throw new StateLockException(
          lock + " exists but is unreadable (" + e.getMessage() + "); another draftwatch may be"
              + " writing it. Remove it only if no draftwatch process is running",
          e);
    }
  }

  private byte[] bytes(LockHolder holder) {
    try {
      return json.writeValueAsBytes(holder.toJson());
    } catch (IOException e) {
      throw new StateLockException("cannot serialize lock holder: " + e.getMessage(), e);
    }
  }
}
