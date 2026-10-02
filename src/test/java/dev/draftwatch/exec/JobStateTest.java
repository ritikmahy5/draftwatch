package dev.draftwatch.exec;

import static dev.draftwatch.exec.JobState.CANCELLED;
import static dev.draftwatch.exec.JobState.CREATED;
import static dev.draftwatch.exec.JobState.FAILED;
import static dev.draftwatch.exec.JobState.RUNNING;
import static dev.draftwatch.exec.JobState.SUBMITTED;
import static dev.draftwatch.exec.JobState.SUCCEEDED;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public class JobStateTest {
  /** The table in ARCHITECTURE.md, "Job state machine", row by row. */
  private static final List<JobState[]> LEGAL =
      List.of(
          new JobState[] {CREATED, SUBMITTED},
          new JobState[] {CREATED, CANCELLED},
          new JobState[] {CREATED, FAILED},
          new JobState[] {SUBMITTED, RUNNING},
          new JobState[] {SUBMITTED, FAILED},
          new JobState[] {SUBMITTED, CANCELLED},
          new JobState[] {RUNNING, SUBMITTED},
          new JobState[] {RUNNING, SUCCEEDED},
          new JobState[] {RUNNING, FAILED},
          new JobState[] {RUNNING, CANCELLED},
          new JobState[] {FAILED, CREATED});

  private static boolean isLegal(JobState from, JobState to) {
    for (JobState[] row : LEGAL) {
      if (row[0] == from && row[1] == to) {
        return true;
      }
    }
    return false;
  }

  @Test
  public void tableHasExactlyTheDocumentedTransitions() {
    Set<String> actual = new HashSet<>();
    for (JobState from : JobState.values()) {
      for (JobState to : from.successors()) {
        actual.add(from + "->" + to);
      }
    }
    Set<String> expected = new HashSet<>();
    for (JobState[] row : LEGAL) {
      expected.add(row[0] + "->" + row[1]);
    }
    assertEquals(expected, actual);
  }

  @Test
  public void everyIllegalTransitionThrows() {
    int illegal = 0;
    for (JobState from : JobState.values()) {
      for (JobState to : JobState.values()) {
        if (isLegal(from, to)) {
          JobState.checkTransition(from, to);
          continue;
        }
        illegal++;
        try {
          JobState.checkTransition(from, to);
          fail(from + " -> " + to + " should be illegal");
        } catch (IllegalJobTransitionException e) {
          assertEquals(from, e.from());
          assertEquals(to, e.to());
          assertTrue(e.getMessage(), e.getMessage().contains(from + " -> " + to));
        }
      }
    }
    assertEquals("36 pairs minus 11 legal ones", 25, illegal);
  }

  @Test
  public void onlyCreatedSubmittedAndRunningAreActive() {
    for (JobState s : JobState.values()) {
      assertEquals(s.toString(), s == CREATED || s == SUBMITTED || s == RUNNING, s.isActive());
    }
    assertFalse(SUCCEEDED.isActive());
  }
}
