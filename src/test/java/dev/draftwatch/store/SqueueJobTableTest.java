package dev.draftwatch.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import dev.draftwatch.exec.ExecutorException;
import dev.draftwatch.exec.slurm.SlurmCli;
import dev.draftwatch.testing.ReplayCommandRunner;
import dev.draftwatch.testing.SlurmScenario;
import org.junit.Test;

/** squeue answers, replayed from the executor's fixtures, as lock-holder liveness (D62). */
public class SqueueJobTableTest {
  private final ReplayCommandRunner commands = new ReplayCommandRunner();
  private final SqueueJobTable table = new SqueueJobTable(new SlurmCli(commands));

  private boolean aliveAt(String fixture, int observation) {
    SlurmScenario s = SlurmScenario.load(fixture);
    commands.observe(s.observations().get(observation).results());
    return table.isAlive(s.jobId());
  }

  @Test
  public void runningAndPendingJobsAreAlive() {
    assertTrue(aliveAt("synthetic_running_states", 0));
    assertTrue(aliveAt("synthetic_pending", 0));
    assertTrue("an unknown state counts as alive", aliveAt("synthetic_unknown_state", 0));
  }

  @Test
  public void finishedAndForgottenJobsAreGone() {
    assertFalse("listed, but COMPLETED", aliveAt("synthetic_completed", 1));
    assertFalse("Invalid job id specified", aliveAt("synthetic_cancelled", 1));
    assertFalse("no line", aliveAt("synthetic_gone_with_empty_output", 1));
  }

  @Test
  public void squeueFailureIsAnError() {
    try {
      aliveAt("synthetic_squeue_error", 0);
      fail("expected ExecutorException");
    } catch (ExecutorException e) {
      assertTrue(e.getMessage().contains("Unable to contact slurm controller"));
    }
    assertEquals(1, commands.calls("squeue").size());
  }
}
