package dev.draftwatch.config;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.draftwatch.domain.Decoding;
import dev.draftwatch.domain.Probe;
import dev.draftwatch.fingerprint.Sha256;

/**
 * Computes the probe hash: SHA-256 of the canonical JSON of everything that determines whether
 * two measurements are comparable (MEASUREMENT_CONTRACT.md, "Comparability").
 *
 * <p>The hashed object is:
 *
 * <pre>{@code
 * {"decoding":{"dtype":..,"max_new_tokens":..,"num_speculative_tokens":..,"temperature":..},
 *  "draft":{"fingerprint":..,"structure":..},
 *  "estimator":..,
 *  "prompt_set_sha256":..,
 *  "seeds":[..]}
 * }</pre>
 *
 * <p>Probe id, draft id, and file paths are deliberately left out: renaming a probe or moving a
 * file does not change what is measured.
 */
public final class ProbeHasher {
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private ProbeHasher() {}

  /** Lowercase hex probe hash. */
  public static String hash(Probe probe, String draftFingerprint, String promptSetSha256) {
    return Sha256.hex(CanonicalJson.bytes(input(probe, draftFingerprint, promptSetSha256)));
  }

  /** The exact canonical text that {@link #hash} digests; exposed for tests and diagnostics. */
  public static String canonicalInput(
      Probe probe, String draftFingerprint, String promptSetSha256) {
    return CanonicalJson.write(input(probe, draftFingerprint, promptSetSha256));
  }

  private static ObjectNode input(Probe probe, String draftFingerprint, String promptSetSha256) {
    ObjectNode root = NODES.objectNode();
    root.set("decoding", decodingJson(probe.decoding()));
    ObjectNode draft = root.putObject("draft");
    draft.put("fingerprint", draftFingerprint);
    draft.put("structure", probe.draft().structure().wireName());
    root.put("estimator", probe.estimator().wireName());
    root.put("prompt_set_sha256", promptSetSha256);
    ArrayNode seeds = root.putArray("seeds");
    for (int seed : probe.seeds()) {
      seeds.add(seed);
    }
    return root;
  }

  /** The decoding object exactly as the contract's report schema spells it. */
  public static ObjectNode decodingJson(Decoding decoding) {
    ObjectNode node = NODES.objectNode();
    node.put("temperature", decoding.temperature());
    node.put("max_new_tokens", decoding.maxNewTokens());
    node.put("num_speculative_tokens", decoding.numSpeculativeTokens());
    node.put("dtype", decoding.dtype());
    return node;
  }
}
