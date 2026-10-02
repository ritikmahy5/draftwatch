package dev.draftwatch.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.draftwatch.config.ConfigLoader;
import dev.draftwatch.config.ConfigValidator;
import dev.draftwatch.domain.ResolvedProbe;
import dev.draftwatch.fingerprint.SampledBlockFingerprinter;
import dev.draftwatch.harness.ProbeResolver;
import dev.draftwatch.harness.PromptSetReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ValidateCommandTest {
  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private final CommandTestSupport cli = new CommandTestSupport();
  private Path dir;
  private Path config;

  @Before
  public void setUp() throws IOException {
    dir = tmp.getRoot().toPath().toAbsolutePath();
    Files.createDirectories(dir.resolve("draft"));
    Files.write(dir.resolve("draft/model.safetensors"), new byte[] {9, 8, 7});
    Files.writeString(dir.resolve("prompts.jsonl"), "{\"prompt\":\"a\"}\n{\"prompt\":\"b\"}\n");
    config = dir.resolve("draftwatch.yaml");
    writeConfig("sampled", "draft");
  }

  private void writeConfig(String fingerprint, String draftPath) throws IOException {
    Files.writeString(
        config,
        String.join(
            "\n",
            "fingerprint: " + fingerprint,
            "executor: { type: local, max_retries: 1 }",
            "harness: { command: [python, measure.py] }",
            "probes:",
            "  - id: p",
            "    draft: { id: d, path: " + draftPath + ", structure: chain }",
            "    prompts: { path: prompts.jsonl }",
            "    decoding: { temperature: 0, max_new_tokens: 8, num_speculative_tokens: 3,"
                + " dtype: bfloat16 }",
            "    seeds: [0]",
            "targets:",
            "  - name: run",
            "    checkpoint_dirs: [ckpts]",
            "    checkpoint_type: full",
            "    probes: [p]",
            ""));
  }

  private int validate() {
    return cli.run("validate", "--config", config.toString());
  }

  @Test
  public void printsResolvedProbeHashAndDefaultTriggerChain() {
    assertEquals(cli.err(), Cli.EXIT_OK, validate());
    ResolvedProbe expected =
        new ProbeResolver(
                new SampledBlockFingerprinter(), new PromptSetReader(new ObjectMapper()))
            .resolve(new ConfigLoader(new ConfigValidator()).load(config).probes().get(0));
    String out = cli.out();
    assertTrue(out, out.contains("  probe hash:        " + expected.hash() + "\n"));
    assertTrue(out, out.contains("  draft fingerprint: " + expected.draftFingerprint() + "\n"));
    assertTrue(out, out.contains("(2 prompts, sha256 " + expected.promptSet().sha256() + ")"));
    assertTrue(
        out,
        out.contains(
            "  decoding:          {\"dtype\":\"bfloat16\",\"max_new_tokens\":8,"
                + "\"num_speculative_tokens\":3,\"temperature\":0}\n"));
    assertTrue(
        out,
        out.contains(
            "  triggers:          not_already_measured -> max_pending(4) -> always_final"
                + " -> every_n_steps(1)\n"));
    assertTrue(
        out,
        out.contains(
            "  detector:          paired_bootstrap(metric=alpha, confidence=0.95,"
                + " min_effect=0, resamples=2000, bootstrap_seed=0)\n"));
    assertTrue(out, out.contains(dir.resolve("ckpts") + " (does not exist yet)"));
    assertTrue(out, out.endsWith("OK: 1 probe, 1 target\n"));
    assertEquals("", cli.err());
  }

  @Test
  public void fullFingerprintMethodIsUsedForDrafts() throws IOException {
    writeConfig("full", "draft");
    assertEquals(Cli.EXIT_OK, validate());
    assertTrue(cli.out(), cli.out().contains("  draft fingerprint: full-"));
  }

  @Test
  public void invalidConfigFailsWithFieldErrorsOnStderr() throws IOException {
    Files.writeString(
        config, Files.readString(config).replace("checkpoint_type: full", "checkpoint_type: lora"));
    assertEquals(Cli.EXIT_FAILURE, validate());
    assertEquals("", cli.out());
    assertTrue(
        cli.err(),
        cli.err()
            .contains(
                "targets[0].checkpoint_type: must be one of full | adapter, was 'lora'"));
  }

  @Test
  public void unresolvableDraftFailsNamingTheProbe() throws IOException {
    writeConfig("sampled", "missing-draft");
    assertEquals(Cli.EXIT_FAILURE, validate());
    assertEquals("", cli.out());
    assertTrue(cli.err(), cli.err().contains("1 probe cannot be resolved"));
    assertTrue(cli.err(), cli.err().contains("probes[0] (p): "));
    assertTrue(cli.err(), cli.err().contains("missing-draft: not a directory"));
  }

  @Test
  public void invalidPromptFileFailsNamingTheLine() throws IOException {
    Files.writeString(dir.resolve("prompts.jsonl"), "{\"prompt\":\"a\"}\nnot json\n");
    assertEquals(Cli.EXIT_FAILURE, validate());
    assertTrue(cli.err(), cli.err().contains("line 2 is not valid JSON"));
  }

  @Test
  public void missingConfigFileFails() {
    String missing = dir.resolve("no.yaml").toString();
    assertEquals(Cli.EXIT_FAILURE, cli.run("validate", "--config", missing));
    assertTrue(cli.err(), cli.err().contains("file not found"));
  }

  @Test
  public void initTemplateFailsValidationUntilPlaceholdersAreReplaced() {
    Path fresh = dir.resolve("fresh").resolve("draftwatch.yaml");
    assertEquals(Cli.EXIT_OK, cli.run("init", "--config", fresh.toString()));
    assertEquals(Cli.EXIT_FAILURE, cli.run("validate", "--config", fresh.toString()));
    assertTrue(cli.err(), cli.err().contains("/path/to/draft: not a directory"));
  }

  @Test
  public void rejectsArguments() {
    assertEquals(Cli.EXIT_USAGE, cli.run("validate", "now", "--config", config.toString()));
    assertTrue(cli.err(), cli.err().contains("draftwatch validate: unexpected argument 'now'"));
  }
}
