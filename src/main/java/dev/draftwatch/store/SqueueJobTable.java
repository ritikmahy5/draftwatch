package dev.draftwatch.store;

import dev.draftwatch.exec.slurm.QueueEntry;
import dev.draftwatch.exec.slurm.SlurmCli;
import dev.draftwatch.exec.slurm.SlurmJobId;
import java.util.Objects;

/** {@link SlurmJobTable} asking squeue with the executor's query (DECISIONS.md D58, D62). */
public final class SqueueJobTable implements SlurmJobTable {
  private final SlurmCli cli;

  public SqueueJobTable(SlurmCli cli) {
    this.cli = Objects.requireNonNull(cli, "cli");
  }

  @Override
  public boolean isAlive(String slurmJobId) {
    return cli.queue(SlurmJobId.parse(slurmJobId)).map(QueueEntry::isAlive).orElse(false);
  }
}
