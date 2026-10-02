package dev.draftwatch.harness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import dev.draftwatch.domain.Checkpoint;
import dev.draftwatch.domain.CheckpointType;
import dev.draftwatch.domain.Decoding;
import dev.draftwatch.domain.Draft;
import dev.draftwatch.domain.DraftStructure;
import dev.draftwatch.domain.Estimator;
import dev.draftwatch.domain.Probe;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.junit.Test;

public class HarnessInvocationTest {
  private static final Path OUT = Paths.get("/state/raw/j/attempt-1/report.json");

  private static Probe probe(String temperature, List<Integer> seeds) {
    return Probe.of(
        "p",
        Draft.of("my-draft", Paths.get("/drafts/d"), DraftStructure.CHAIN),
        Paths.get("/probes/chat.jsonl"),
        Decoding.of(new BigDecimal(temperature), 256, 5, "bfloat16"),
        Estimator.SIMPLE_MEAN,
        seeds);
  }

  private static Checkpoint.Builder checkpoint(CheckpointType type) {
    return Checkpoint.builder()
        .targetName("run")
        .path(Paths.get("/runs/checkpoint-500"))
        .step(500)
        .fingerprint("sampled-x")
        .type(type);
  }

  @Test
  public void fullCheckpointArgumentsFollowTheContractOrder() {
    HarnessInvocation inv =
        HarnessInvocation.builder()
            .harnessCommand(List.of("python", "measure.py"))
            .checkpoint(checkpoint(CheckpointType.FULL).build())
            .probe(probe("0.0", List.of(0)))
            .out(OUT)
            .build();
    assertEquals(
        List.of(
            "python",
            "measure.py",
            "--target-checkpoint",
            "/runs/checkpoint-500",
            "--draft-id",
            "my-draft",
            "--draft-path",
            "/drafts/d",
            "--prompts",
            "/probes/chat.jsonl",
            "--decoding-json",
            "{\"dtype\":\"bfloat16\",\"max_new_tokens\":256,\"num_speculative_tokens\":5,"
                + "\"temperature\":0}",
            "--estimator",
            "simple_mean",
            "--seeds",
            "0",
            "--out",
            OUT.toString()),
        inv.command());
    assertEquals(OUT, inv.out());
  }

  @Test
  public void adapterCheckpointPassesBaseModelAndAllSeeds() {
    HarnessInvocation inv =
        HarnessInvocation.builder()
            .harnessCommand(List.of("h"))
            .checkpoint(checkpoint(CheckpointType.ADAPTER).baseModel(Paths.get("/base")).build())
            .probe(probe("0.7", List.of(3, 1, 2)))
            .out(OUT)
            .build();
    List<String> args = inv.arguments();
    int base = args.indexOf("--base-model");
    assertEquals("--base-model follows --target-checkpoint", 2, base);
    assertEquals("/base", args.get(base + 1));
    assertEquals("3,1,2", args.get(args.indexOf("--seeds") + 1));
    assertTrue(args.get(args.indexOf("--decoding-json") + 1).contains("\"temperature\":0.7"));
  }

  @Test
  public void missingInputIsNamed() {
    try {
      HarnessInvocation.builder()
          .harnessCommand(List.of("h"))
          .checkpoint(checkpoint(CheckpointType.FULL).build())
          .out(OUT)
          .build();
      fail("expected NullPointerException");
    } catch (NullPointerException e) {
      assertEquals("probe must be set", e.getMessage());
    }
  }

  @Test(expected = IllegalArgumentException.class)
  public void emptyHarnessCommandIsRejected() {
    HarnessInvocation.builder()
        .harnessCommand(List.of())
        .checkpoint(checkpoint(CheckpointType.FULL).build())
        .probe(probe("0", List.of(0)))
        .out(OUT)
        .build();
  }
}
