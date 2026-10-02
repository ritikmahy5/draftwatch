package dev.draftwatch.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** The contents of {@code <state>/lock}: who holds it, since when, and for which command. */
public final class LockHolder {
  private final String host;
  private final long pid;
  private final Optional<Instant> pidStart;
  private final Optional<String> slurmJobId;
  private final Instant acquiredAt;
  private final String command;

  private LockHolder(
      String host,
      long pid,
      Optional<Instant> pidStart,
      Optional<String> slurmJobId,
      Instant acquiredAt,
      String command) {
    this.host = host;
    this.pid = pid;
    this.pidStart = pidStart;
    this.slurmJobId = slurmJobId;
    this.acquiredAt = acquiredAt;
    this.command = command;
  }

  public static LockHolder of(
      String host,
      long pid,
      Optional<Instant> pidStart,
      Optional<String> slurmJobId,
      Instant acquiredAt,
      String command) {
    return new LockHolder(
        Objects.requireNonNull(host, "host"),
        pid,
        Objects.requireNonNull(pidStart, "pidStart"),
        Objects.requireNonNull(slurmJobId, "slurmJobId"),
        Objects.requireNonNull(acquiredAt, "acquiredAt"),
        Objects.requireNonNull(command, "command"));
  }

  public String host() {
    return host;
  }

  public long pid() {
    return pid;
  }

  public Optional<Instant> pidStart() {
    return pidStart;
  }

  public Optional<String> slurmJobId() {
    return slurmJobId;
  }

  public Instant acquiredAt() {
    return acquiredAt;
  }

  /** The draftwatch command holding the lock, for example {@code submit}. */
  public String command() {
    return command;
  }

  ObjectNode toJson() {
    ObjectNode node = JsonNodeFactory.instance.objectNode();
    node.put("host", host);
    node.put("pid", pid);
    if (pidStart.isPresent()) {
      node.put("pid_start", pidStart.get().toString());
    } else {
      node.putNull("pid_start");
    }
    if (slurmJobId.isPresent()) {
      node.put("slurm_job_id", slurmJobId.get());
    } else {
      node.putNull("slurm_job_id");
    }
    node.put("acquired_at", acquiredAt.toString());
    node.put("command", command);
    return node;
  }

  /** @throws IllegalArgumentException naming the first malformed field */
  static LockHolder fromJson(JsonNode json) {
    Fields f = new Fields(json, "");
    return of(
        f.text("host"),
        f.longValue("pid"),
        f.optionalInstant("pid_start"),
        f.optionalText("slurm_job_id"),
        f.instant("acquired_at"),
        f.text("command"));
  }

  /** For example {@code submit (pid 4242 on node1, since 2026-10-01T12:00:00Z)}. */
  @Override
  public String toString() {
    return command + " (pid " + pid + " on " + host
        + slurmJobId.map(id -> ", Slurm job " + id).orElse("") + ", since " + acquiredAt + ")";
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof LockHolder)) {
      return false;
    }
    LockHolder that = (LockHolder) o;
    return host.equals(that.host)
        && pid == that.pid
        && pidStart.equals(that.pidStart)
        && slurmJobId.equals(that.slurmJobId)
        && acquiredAt.equals(that.acquiredAt)
        && command.equals(that.command);
  }

  @Override
  public int hashCode() {
    return Objects.hash(host, pid, pidStart, slurmJobId, acquiredAt, command);
  }
}
