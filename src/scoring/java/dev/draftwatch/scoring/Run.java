package dev.draftwatch.scoring;

/** The two fine-tuning runs of a family: run 1 is the trunk (the control), run 2 the branch. */
public enum Run {
  RUN1("run1"),
  RUN2("run2");

  private final String suffix;

  Run(String suffix) {
    this.suffix = suffix;
  }

  /** The suffix of the run's target name, as in {@code qwen-run1}. */
  public String suffix() {
    return suffix;
  }
}
