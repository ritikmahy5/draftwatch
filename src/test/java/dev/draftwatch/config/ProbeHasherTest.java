package dev.draftwatch.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import dev.draftwatch.domain.Decoding;
import dev.draftwatch.domain.Draft;
import dev.draftwatch.domain.DraftStructure;
import dev.draftwatch.domain.Estimator;
import dev.draftwatch.domain.Probe;
import dev.draftwatch.fingerprint.Sha256;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.List;
import org.junit.Test;

public class ProbeHasherTest {
  private static final String FP = "sampled-" + "1".repeat(64);
  private static final String PROMPTS = "2".repeat(64);

  private static Probe probe(
      String id, String draftId, String temperature, Estimator estimator, List<Integer> seeds) {
    return Probe.of(
        id,
        Draft.of(draftId, Paths.get("/drafts/" + draftId), DraftStructure.CHAIN),
        Paths.get("/prompts/" + id + ".jsonl"),
        Decoding.of(new BigDecimal(temperature), 256, 5, "bfloat16"),
        estimator,
        seeds);
  }

  private static Probe base() {
    return probe("chat", "d1", "0", Estimator.TOKEN_WEIGHTED, List.of(0));
  }

  @Test
  public void canonicalInputHasExactlyTheDocumentedShape() {
    assertEquals(
        "{\"decoding\":{\"dtype\":\"bfloat16\",\"max_new_tokens\":256,"
            + "\"num_speculative_tokens\":5,\"temperature\":0},"
            + "\"draft\":{\"fingerprint\":\""
            + FP
            + "\",\"structure\":\"chain\"},"
            + "\"estimator\":\"token_weighted\","
            + "\"prompt_set_sha256\":\""
            + PROMPTS
            + "\",\"seeds\":[0]}",
        ProbeHasher.canonicalInput(base(), FP, PROMPTS));
  }

  @Test
  public void hashIsSha256OfCanonicalInput() {
    String input = ProbeHasher.canonicalInput(base(), FP, PROMPTS);
    assertEquals(
        Sha256.hex(input.getBytes(StandardCharsets.UTF_8)), ProbeHasher.hash(base(), FP, PROMPTS));
  }

  @Test
  public void zeroAndZeroPointZeroTemperatureHashIdentically() {
    Probe zero = probe("chat", "d1", "0", Estimator.TOKEN_WEIGHTED, List.of(0));
    Probe zeroPointZero = probe("chat", "d1", "0.0", Estimator.TOKEN_WEIGHTED, List.of(0));
    assertEquals(ProbeHasher.hash(zero, FP, PROMPTS), ProbeHasher.hash(zeroPointZero, FP, PROMPTS));
  }

  @Test
  public void namesAndPathsDoNotAffectHash() {
    Probe renamed = probe("other-name", "other-draft", "0", Estimator.TOKEN_WEIGHTED, List.of(0));
    assertEquals(ProbeHasher.hash(base(), FP, PROMPTS), ProbeHasher.hash(renamed, FP, PROMPTS));
  }

  @Test
  public void everyComparabilityInputChangesHash() {
    String h = ProbeHasher.hash(base(), FP, PROMPTS);
    assertNotEquals(h, ProbeHasher.hash(base(), "sampled-" + "3".repeat(64), PROMPTS));
    assertNotEquals(h, ProbeHasher.hash(base(), FP, "4".repeat(64)));
    assertNotEquals(
        h,
        ProbeHasher.hash(
            probe("chat", "d1", "0", Estimator.SIMPLE_MEAN, List.of(0)), FP, PROMPTS));
    assertNotEquals(
        h,
        ProbeHasher.hash(
            probe("chat", "d1", "0.7", Estimator.TOKEN_WEIGHTED, List.of(0)), FP, PROMPTS));
    assertNotEquals(
        h,
        ProbeHasher.hash(
            probe("chat", "d1", "0", Estimator.TOKEN_WEIGHTED, List.of(1)), FP, PROMPTS));
  }

  @Test
  public void seedOrderIsPartOfTheHash() {
    Probe ab = probe("chat", "d1", "0.7", Estimator.TOKEN_WEIGHTED, List.of(0, 1));
    Probe ba = probe("chat", "d1", "0.7", Estimator.TOKEN_WEIGHTED, List.of(1, 0));
    assertNotEquals(ProbeHasher.hash(ab, FP, PROMPTS), ProbeHasher.hash(ba, FP, PROMPTS));
  }
}
