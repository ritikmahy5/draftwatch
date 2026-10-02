package dev.draftwatch.exec.slurm;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Slurm job id as {@code sbatch --parsable} prints it: "the job ID number and the cluster name
 * if present … separated by a semicolon" (sbatch.html; DECISIONS.md D58). The cluster, when
 * present, is passed to every later command as {@code --clusters}.
 */
public final class SlurmJobId {
  private static final Pattern FORMAT = Pattern.compile("(\\d+)(?:;([^\\s;|]+))?");

  private final String id;
  private final Optional<String> cluster;

  private SlurmJobId(String id, Optional<String> cluster) {
    this.id = id;
    this.cluster = cluster;
  }

  /**
   * Parses {@code 4242} or {@code 4242;explorer}.
   *
   * @throws IllegalArgumentException naming the text if it is neither
   */
  public static SlurmJobId parse(String text) {
    Matcher m = FORMAT.matcher(Objects.requireNonNull(text, "text"));
    if (!m.matches()) {
      throw new IllegalArgumentException(
          "'" + text + "' is not a Slurm job id (digits, optionally ';' and a cluster name)");
    }
    return new SlurmJobId(m.group(1), Optional.ofNullable(m.group(2)));
  }

  /** The numeric job id. */
  public String id() {
    return id;
  }

  public Optional<String> cluster() {
    return cluster;
  }

  /** {@code --clusters=<name>} when the job ran on a named cluster; otherwise nothing. */
  List<String> clusterArgs() {
    return cluster.map(c -> List.of("--clusters=" + c)).orElse(List.of());
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof SlurmJobId)) {
      return false;
    }
    SlurmJobId that = (SlurmJobId) o;
    return id.equals(that.id) && cluster.equals(that.cluster);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id, cluster);
  }

  /** The form {@link #parse} reads back, stored as the handle's native id. */
  @Override
  public String toString() {
    return id + cluster.map(c -> ";" + c).orElse("");
  }
}
