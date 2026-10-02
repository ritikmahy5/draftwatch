package dev.draftwatch.harness;

import dev.draftwatch.domain.AdapterHandling;
import dev.draftwatch.domain.CheckpointType;
import dev.draftwatch.domain.Decoding;
import dev.draftwatch.domain.Estimator;
import dev.draftwatch.domain.PromptSet;
import dev.draftwatch.domain.ResolvedProbe;
import java.util.List;
import java.util.Objects;

/** What the engine passed to the harness, which the report's identity fields must echo. */
public final class ExpectedReport {
  /** The report schema version this parser implements. */
  public static final int SCHEMA_VERSION = 1;

  private final Estimator estimator;
  private final String draftId;
  private final Decoding decoding;
  private final PromptSet promptSet;
  private final List<Integer> seeds;
  private final AdapterHandling adapterHandling;

  private ExpectedReport(
      Estimator estimator,
      String draftId,
      Decoding decoding,
      PromptSet promptSet,
      List<Integer> seeds,
      AdapterHandling adapterHandling) {
    this.estimator = estimator;
    this.draftId = draftId;
    this.decoding = decoding;
    this.promptSet = promptSet;
    this.seeds = seeds;
    this.adapterHandling = adapterHandling;
  }

  /** The expectations for measuring {@code probe} on a checkpoint of {@code type}. */
  public static ExpectedReport of(ResolvedProbe probe, CheckpointType type) {
    Objects.requireNonNull(probe, "probe");
    Objects.requireNonNull(type, "type");
    return new ExpectedReport(
        probe.probe().estimator(),
        probe.probe().draft().id(),
        probe.probe().decoding(),
        probe.promptSet(),
        probe.probe().seeds(),
        type == CheckpointType.ADAPTER ? AdapterHandling.MERGED : AdapterHandling.NONE);
  }

  public Estimator estimator() {
    return estimator;
  }

  public String draftId() {
    return draftId;
  }

  public Decoding decoding() {
    return decoding;
  }

  public PromptSet promptSet() {
    return promptSet;
  }

  public List<Integer> seeds() {
    return seeds;
  }

  /** {@code merged} exactly when {@code --base-model} is passed, for adapter checkpoints. */
  public AdapterHandling adapterHandling() {
    return adapterHandling;
  }
}
