package dev.draftwatch.exec.slurm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import dev.draftwatch.exec.ExecutorException;
import dev.draftwatch.testing.ReplayCommandRunner;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.Test;

/** Parsing of squeue and sacct output, and what is refused as unexpected. */
public class SlurmCliTest {
  private static final SlurmJobId JOB = SlurmJobId.parse("4242");

  private final ReplayCommandRunner commands = new ReplayCommandRunner();
  private final SlurmCli cli = new SlurmCli(commands);

  private void squeuePrints(String stdout) {
    commands.observe(List.of(CommandResult.of(SlurmCli.squeueArgv(JOB), 0, stdout, "")));
  }

  private void sacctPrints(String stdout) {
    commands.observe(List.of(CommandResult.of(SlurmCli.sacctArgv(JOB), 0, stdout, "")));
  }

  private static void assertRefused(Runnable call, String fragment) {
    try {
      call.run();
      fail("expected ExecutorException containing " + fragment);
    } catch (ExecutorException e) {
      assertTrue(e.getMessage(), e.getMessage().contains(fragment));
    }
  }

  @Test
  public void jobIdsParseWithAndWithoutACluster() {
    assertEquals("4242", SlurmJobId.parse("4242").id());
    assertEquals(Optional.of("explorer"), SlurmJobId.parse("4242;explorer").cluster());
    assertEquals("4242;explorer", SlurmJobId.parse("4242;explorer").toString());
    for (String bad : new String[] {"", "42a", "4242;", "4242;a;b", "Submitted batch job 4242"}) {
      try {
        SlurmJobId.parse(bad);
        fail(bad);
      } catch (IllegalArgumentException e) {
        assertTrue(e.getMessage().contains("is not a Slurm job id"));
      }
    }
  }

  @Test
  public void stateNamesIgnoreSacctsSuffixes() {
    assertEquals(Optional.of(SlurmState.CANCELLED), SlurmState.parse("CANCELLED by 1001"));
    assertEquals(Optional.of(SlurmState.CANCELLED), SlurmState.parse("CANCELLED+"));
    assertEquals(Optional.of(SlurmState.OUT_OF_MEMORY), SlurmState.parse("OUT_OF_MEMORY"));
    assertEquals(Optional.empty(), SlurmState.parse("EXPEDITING"));
    assertEquals(Optional.empty(), SlurmState.parse("running"));
  }

  @Test
  public void squeueLineIsParsedWithItsStartTimeOffset() {
    squeuePrints("4242|RUNNING|2026-10-01T12:00:05-0400\n");
    QueueEntry e = cli.queue(JOB).orElseThrow();
    assertEquals(Optional.of(SlurmState.RUNNING), e.state());
    assertEquals(Optional.of(Instant.parse("2026-10-01T16:00:05Z")), e.start());
    squeuePrints("4242|PENDING|N/A\n");
    assertEquals(Optional.empty(), cli.queue(JOB).orElseThrow().start());
  }

  @Test
  public void squeueLinesForOtherJobsAreIgnoredButDuplicatesAreNot() {
    squeuePrints("4243|RUNNING|N/A\n");
    assertEquals(Optional.empty(), cli.queue(JOB));
    squeuePrints("4242|RUNNING|N/A\n4242|PENDING|N/A\n");
    assertRefused(() -> cli.queue(JOB), "listed job 4242 2 times");
  }

  @Test
  public void malformedOutputIsRefused() {
    squeuePrints("4242 RUNNING\n");
    assertRefused(() -> cli.queue(JOB), "has 1 fields, expected 3");
    squeuePrints("4242|RUNNING|Oct 1 12:00\n");
    assertRefused(() -> cli.queue(JOB), "time 'Oct 1 12:00' is not in SLURM_TIME_FORMAT");
    sacctPrints("4242|COMPLETED|0|Unknown|Unknown\n");
    assertRefused(() -> cli.accounting(JOB), "ExitCode '0' is not N:M");
    sacctPrints("4242|COMPLETED|0:0|Unknown|Unknown\n4242|PENDING|0:0|Unknown|Unknown\n");
    assertRefused(() -> cli.accounting(JOB), "sacct returned 2 records for job 4242");
  }

  @Test
  public void sacctRecordKeepsExitCodeSignalAndTimes() {
    sacctPrints("4242|FAILED|3:0|2026-10-01T12:00:05-0400|2026-10-01T12:05:00-0400\n");
    AccountingRecord r = cli.accounting(JOB).orElseThrow();
    assertEquals(OptionalInt.of(3), r.exitCode());
    assertEquals(OptionalInt.of(0), r.signal());
    assertEquals(Optional.of(Instant.parse("2026-10-01T16:05:00Z")), r.end());
    assertEquals("FAILED, exit code 3:0", r.describe());
    sacctPrints("");
    assertEquals(Optional.empty(), cli.accounting(JOB));
  }

  @Test
  public void failingSacctAndScancelAreErrors() {
    commands.observe(
        List.of(
            CommandResult.of(
                SlurmCli.sacctArgv(JOB), 1, "",
                "sacct: error: Problem talking to the database: Connection refused\n"),
            CommandResult.of(
                SlurmCli.scancelArgv(JOB), 1, "", "scancel: error: Kill job error\n")));
    assertRefused(() -> cli.accounting(JOB), "Problem talking to the database");
    assertRefused(() -> cli.cancel(JOB), "cannot cancel Slurm job 4242");
  }
}
