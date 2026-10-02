package dev.draftwatch.testing;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.draftwatch.config.ProbeHasher;
import dev.draftwatch.domain.Checkpoint;
import dev.draftwatch.domain.CheckpointType;
import dev.draftwatch.domain.Decoding;
import dev.draftwatch.domain.Draft;
import dev.draftwatch.domain.DraftStructure;
import dev.draftwatch.domain.Estimator;
import dev.draftwatch.domain.Probe;
import dev.draftwatch.domain.PromptSet;
import dev.draftwatch.domain.ResolvedProbe;
import dev.draftwatch.harness.ExpectedReport;
import dev.draftwatch.harness.HarnessInvocation;
import dev.draftwatch.harness.PromptSetReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A checkpoint, a draft, a prompt file, and a probe on disk, ready for a fake-harness run. Two
 * variants cover both estimators, one and several seeds, and full and adapter checkpoints.
 */
public final class ReportScenario {
  /** A stand-in draft fingerprint; scenarios do not fingerprint real weights. */
  public static final String DRAFT_FINGERPRINT = "sampled-" + "d".repeat(64);

  private final Path dir;
  private final String fixture;
  private final Checkpoint checkpoint;
  private final ResolvedProbe probe;

  private ReportScenario(Path dir, String fixture, Checkpoint checkpoint, ResolvedProbe probe) {
    this.dir = dir;
    this.fixture = fixture;
    this.checkpoint = checkpoint;
    this.probe = probe;
  }

  /** Greedy decoding, one seed, {@code token_weighted}, full checkpoint, 3 prompts, k = 3. */
  public static ReportScenario greedy(Path dir) {
    return create(
        dir, "synthetic_three_prompts.json", "0", List.of(0), Estimator.TOKEN_WEIGHTED, false);
  }

  /** Sampling, seeds 3 and 5, {@code simple_mean}, adapter checkpoint, 3 prompts, k = 3. */
  public static ReportScenario sampledAdapter(Path dir) {
    return create(
        dir, "synthetic_two_seeds.json", "0.7", List.of(3, 5), Estimator.SIMPLE_MEAN, true);
  }

  /** Like {@link #greedy} but on a fixture where one prompt proposes nothing. */
  public static ReportScenario excludedPrompt(Path dir, Estimator estimator) {
    return create(dir, "synthetic_excluded_prompt.json", "0", List.of(0), estimator, false);
  }

  private static ReportScenario create(
      Path dir,
      String fixture,
      String temperature,
      List<Integer> seeds,
      Estimator estimator,
      boolean adapter) {
    try {
      Path ckpt = Files.createDirectories(dir.resolve("run/checkpoint-100"));
      Path draft = Files.createDirectories(dir.resolve("draft"));
      Path prompts = FakeHarness.writePrompts(dir, 3);
      Checkpoint.Builder checkpoint =
          Checkpoint.builder()
              .targetName("run")
              .path(ckpt)
              .step(100)
              .fingerprint("sampled-" + "c".repeat(64))
              .type(adapter ? CheckpointType.ADAPTER : CheckpointType.FULL);
      if (adapter) {
        checkpoint.baseModel(
            Files.createDirectories(dir.resolve("base")), "sampled-" + "b".repeat(64));
      }
      Probe probe =
          Probe.of(
              "probe",
              Draft.of("draft", draft, DraftStructure.CHAIN),
              prompts,
              Decoding.of(new BigDecimal(temperature), 16, 3, "bfloat16"),
              estimator,
              seeds);
      PromptSet promptSet = new PromptSetReader(new ObjectMapper()).read(prompts);
      String hash = ProbeHasher.hash(probe, DRAFT_FINGERPRINT, promptSet.sha256());
      return new ReportScenario(
          dir,
          fixture,
          checkpoint.build(),
          ResolvedProbe.of(probe, DRAFT_FINGERPRINT, promptSet, hash));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public Path dir() {
    return dir;
  }

  public Checkpoint checkpoint() {
    return checkpoint;
  }

  public ResolvedProbe probe() {
    return probe;
  }

  public ExpectedReport expected() {
    return ExpectedReport.of(probe, checkpoint.type());
  }

  public Path reportPath() {
    return dir.resolve("report.json");
  }

  /** The fake-harness command for this scenario's fixture plus {@code extraEnvironment}. */
  public List<String> harnessCommand(Map<String, String> extraEnvironment) {
    Map<String, String> env = new HashMap<>(extraEnvironment);
    env.put("DRAFTWATCH_FAKE_FIXTURE", FakeHarness.fixture(fixture).toString());
    return FakeHarness.command(env);
  }

  public HarnessInvocation invocation(Map<String, String> extraEnvironment) {
    return HarnessInvocation.builder()
        .harnessCommand(harnessCommand(extraEnvironment))
        .checkpoint(checkpoint)
        .probe(probe.probe())
        .out(reportPath())
        .build();
  }

  /** Runs the fake harness synchronously; returns its exit code. */
  public int run(Map<String, String> extraEnvironment) {
    return FakeHarness.run(invocation(extraEnvironment).command(), dir);
  }
}
