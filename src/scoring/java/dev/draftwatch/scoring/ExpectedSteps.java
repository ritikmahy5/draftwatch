package dev.draftwatch.scoring;

import java.util.ArrayList;
import java.util.List;

/**
 * The checkpoints each run saves (docs/EVALUATION.md, "Fine-tuning runs"): run 1 every 100 steps
 * from 0 to 1,200 (checkpoint 0 is stored under run 1), run 2 densely after the branch point at
 * 600 and every 100 steps from 800.
 */
public final class ExpectedSteps {
  public static final List<Long> RUN1 = run1();
  public static final List<Long> RUN2 =
      List.of(625L, 650L, 675L, 700L, 725L, 750L, 775L, 800L, 900L, 1000L, 1100L, 1200L);

  private ExpectedSteps() {}

  private static List<Long> run1() {
    List<Long> steps = new ArrayList<>();
    for (long s = 0; s <= 1200; s += 100) {
      steps.add(s);
    }
    return List.copyOf(steps);
  }

  public static List<Long> of(Run run) {
    return run == Run.RUN1 ? RUN1 : RUN2;
  }
}
