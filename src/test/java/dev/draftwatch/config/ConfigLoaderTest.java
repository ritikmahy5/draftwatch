package dev.draftwatch.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import dev.draftwatch.domain.Probe;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ConfigLoaderTest {
  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private final ConfigLoader loader = new ConfigLoader(new ConfigValidator());

  /** Stand-ins for content-derived inputs; the hash test varies only the config text. */
  private static final String DRAFT_FP = "sampled-" + "1".repeat(64);
  private static final String PROMPTS_SHA = "2".repeat(64);

  private static String config(String probeBody) {
    return String.join(
        "\n",
        "executor: { type: local, max_retries: 0 }",
        "harness: { command: [python, measure.py] }",
        "probes:",
        "  - " + probeBody,
        "targets:",
        "  - name: run",
        "    checkpoint_dirs: [ckpts]",
        "    checkpoint_type: full",
        "    probes: [p]",
        "");
  }

  private Path write(String name, String text) throws IOException {
    Path file = tmp.getRoot().toPath().resolve(name);
    Files.writeString(file, text);
    return file;
  }

  private String probeHash(String name, String probeBody) throws IOException {
    Probe probe = loader.load(write(name, config(probeBody))).probe("p").orElseThrow();
    return ProbeHasher.hash(probe, DRAFT_FP, PROMPTS_SHA);
  }

  private ConfigError onlyError(Path file) {
    try {
      loader.load(file);
      fail("expected ConfigException");
      return null;
    } catch (ConfigException e) {
      assertEquals(1, e.errors().size());
      return e.errors().get(0);
    }
  }

  // --- ROADMAP M1 "done when": probe hash identical across key order and 0 vs 0.0 ---------

  @Test
  public void probeHashIgnoresKeyOrderAndZeroSpelling() throws IOException {
    String a =
        probeHash(
            "a.yaml",
            "{id: p, draft: {id: d, path: draft, structure: chain}, prompts: {path: p.jsonl},"
                + " decoding: {temperature: 0, max_new_tokens: 256, num_speculative_tokens: 5,"
                + " dtype: bfloat16}, estimator: token_weighted, seeds: [0]}");
    String b =
        probeHash(
            "b.yaml",
            "{seeds: [0], estimator: token_weighted, decoding: {dtype: bfloat16,"
                + " num_speculative_tokens: 5, max_new_tokens: 256, temperature: 0.0},"
                + " prompts: {path: p.jsonl}, draft: {structure: chain, path: draft, id: d},"
                + " id: p}");
    assertEquals(a, b);
  }

  @Test
  public void probeHashIsTheSameForBlockAndFlowStyleYaml() throws IOException {
    String flow =
        probeHash(
            "flow.yaml",
            "{id: p, draft: {id: d, path: draft, structure: chain}, prompts: {path: p.jsonl},"
                + " decoding: {temperature: 0.70, max_new_tokens: 256, num_speculative_tokens: 5,"
                + " dtype: bfloat16}, seeds: [0, 1]}");
    String block =
        probeHash(
            "block.yaml",
            String.join(
                "\n    ",
                "id: p",
                "seeds:",
                "  - 0",
                "  - 1",
                "decoding:",
                "  dtype: bfloat16",
                "  temperature: 0.7",
                "  num_speculative_tokens: 5",
                "  max_new_tokens: 256",
                "draft: {id: d, path: draft, structure: chain}",
                "prompts: {path: p.jsonl}"));
    assertEquals(flow, block);
  }

  @Test
  public void probeHashChangesWithTemperatureValue() throws IOException {
    String greedy =
        probeHash(
            "g.yaml",
            "{id: p, draft: {id: d, path: draft, structure: chain}, prompts: {path: p.jsonl},"
                + " decoding: {temperature: 0, max_new_tokens: 256, num_speculative_tokens: 5,"
                + " dtype: bfloat16}, seeds: [0]}");
    String sampled =
        probeHash(
            "s.yaml",
            "{id: p, draft: {id: d, path: draft, structure: chain}, prompts: {path: p.jsonl},"
                + " decoding: {temperature: 0.001, max_new_tokens: 256,"
                + " num_speculative_tokens: 5, dtype: bfloat16}, seeds: [0]}");
    assertNotEquals(greedy, sampled);
  }

  // --- YAML strictness -----------------------------------------------------------------------

  @Test
  public void duplicateYamlKeyIsRejected() throws IOException {
    Path file =
        write(
            "dup.yaml",
            config(
                "{id: p, id: q, draft: {id: d, path: draft, structure: chain},"
                    + " prompts: {path: p.jsonl}, decoding: {temperature: 0, max_new_tokens: 8,"
                    + " num_speculative_tokens: 2, dtype: bf16}, seeds: [0]}"));
    ConfigError error = onlyError(file);
    assertEquals("<root>", error.field());
    assertTrue(error.message(), error.message().contains("Duplicate field 'id'"));
  }

  @Test
  public void syntaxErrorReportsLine() throws IOException {
    ConfigError error = onlyError(write("bad.yaml", "executor:\n  type: [local\n"));
    assertTrue(error.message(), error.message().startsWith("invalid YAML at line "));
  }

  @Test
  public void secondYamlDocumentIsRejected() throws IOException {
    ConfigError error = onlyError(write("two.yaml", "state_dir: a\n---\nstate_dir: b\n"));
    assertTrue(error.message(), error.message().startsWith("invalid YAML"));
  }

  @Test
  public void missingFileIsReported() {
    Path missing = tmp.getRoot().toPath().resolve("absent.yaml");
    ConfigError error = onlyError(missing);
    assertTrue(error.message(), error.message().contains("file not found"));
  }

  @Test
  public void emptyFileIsReported() throws IOException {
    assertEquals("config file is empty", onlyError(write("empty.yaml", "")).message());
  }

  private static final String SIMPLE_PROBE =
      "{id: p, draft: {id: d, path: draft, structure: chain}, prompts: {path: p.jsonl},"
          + " decoding: {temperature: 0, max_new_tokens: 8, num_speculative_tokens: 2,"
          + " dtype: bf16}, seeds: [0]}";

  @Test
  public void unquotedSlurmTimeIsReadAsText() throws IOException {
    // Jackson YAML 2.22.3 does not apply YAML 1.1 sexagesimal integers, so 00:45:00 stays text.
    String text =
        config(SIMPLE_PROBE)
            .replace(
                "executor: { type: local, max_retries: 0 }",
                "executor:\n  type: slurm\n  max_retries: 0\n  slurm:\n    time: 00:45:00\n"
                    + "    requeue_on_preempt: false");
    Optional<String> time =
        loader.load(write("time.yaml", text)).executor().slurm().orElseThrow().time();
    assertEquals(Optional.of("00:45:00"), time);
  }

  @Test
  public void nanTemperatureIsRejected() throws IOException {
    Path file =
        write("nan.yaml", config(SIMPLE_PROBE.replace("temperature: 0", "temperature: .nan")));
    assertTrue(onlyError(file).message().startsWith("invalid YAML"));
  }

  @Test
  public void relativePathsResolveAgainstConfigFileDirectory() throws IOException {
    Path sub = tmp.newFolder("project").toPath();
    Path file = sub.resolve("draftwatch.yaml");
    Files.writeString(
        file,
        config(
            "{id: p, draft: {id: d, path: ../drafts/d, structure: chain},"
                + " prompts: {path: p.jsonl}, decoding: {temperature: 0, max_new_tokens: 8,"
                + " num_speculative_tokens: 2, dtype: bf16}, seeds: [0]}"));
    DraftwatchConfig c = loader.load(file);
    Path root = tmp.getRoot().toPath().toAbsolutePath();
    assertEquals(root.resolve("drafts/d"), c.probe("p").orElseThrow().draft().path());
    assertEquals(
        sub.toAbsolutePath().resolve("p.jsonl"), c.probe("p").orElseThrow().promptsPath());
    assertEquals(sub.toAbsolutePath().resolve(".draftwatch"), c.stateDir());
  }
}
