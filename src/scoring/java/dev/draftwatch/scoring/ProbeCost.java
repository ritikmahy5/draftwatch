package dev.draftwatch.scoring;

/** The measured cost of one probe at one size. */
public final class ProbeCost {
  private final String probeId;
  private final CostRow cost;

  ProbeCost(String probeId, CostRow cost) {
    this.probeId = probeId;
    this.cost = cost;
  }

  public String probeId() {
    return probeId;
  }

  public CostRow cost() {
    return cost;
  }
}
