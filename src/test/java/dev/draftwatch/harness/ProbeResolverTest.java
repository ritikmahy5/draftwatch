package dev.draftwatch.harness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.draftwatch.config.ProbeHasher;
import dev.draftwatch.domain.Decoding;
import dev.draftwatch.domain.Draft;
import dev.draftwatch.domain.DraftStructure;
import dev.draftwatch.domain.Estimator;
import dev.draftwatch.domain.Probe;
import dev.draftwatch.domain.ResolvedProbe;
import dev.draftwatch.fingerprint.FingerprintException;
import dev.draftwatch.fingerprint.FullFileFingerprinter;
import dev.draftwatch.fingerprint.SampledBlockFingerprinter;
import dev.draftwatch.fingerprint.Sha256;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ProbeResolverTest {
  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private final PromptSetReader prompts = new PromptSetReader(new ObjectMapper());
  private final ProbeResolver resolver =
      new ProbeResolver(new SampledBlockFingerprinter(), prompts);

  private Path draftDir;
  private Path promptFile;

  @Before
  public void setUp() throws IOException {
    draftDir = tmp.newFolder("draft").toPath();
    Files.write(draftDir.resolve("model.safetensors"), new byte[] {1, 2, 3});
    promptFile = tmp.newFile("prompts.jsonl").toPath();
    Files.writeString(promptFile, "{\"prompt\":\"a\"}\n{\"prompt\":\"b\"}\n");
  }

  private Probe probe() {
    return Probe.of(
        "p",
        Draft.of("d", draftDir, DraftStructure.CHAIN),
        promptFile,
        Decoding.of(BigDecimal.ZERO, 16, 3, "bfloat16"),
        Estimator.TOKEN_WEIGHTED,
        List.of(0));
  }

  @Test
  public void resolvesFingerprintPromptSetAndHash() throws IOException {
    ResolvedProbe r = resolver.resolve(probe());
    assertEquals(new SampledBlockFingerprinter().fingerprint(draftDir), r.draftFingerprint());
    assertEquals(2, r.promptSet().promptCount());
    assertEquals(Sha256.hex(Files.readAllBytes(promptFile)), r.promptSet().sha256());
    assertEquals(
        ProbeHasher.hash(probe(), r.draftFingerprint(), r.promptSet().sha256()), r.hash());
  }

  @Test
  public void changingDraftWeightsChangesProbeHash() throws IOException {
    String before = resolver.resolve(probe()).hash();
    Files.write(draftDir.resolve("model.safetensors"), new byte[] {1, 2, 4});
    assertNotEquals(before, resolver.resolve(probe()).hash());
  }

  @Test
  public void changingPromptFileChangesProbeHash() throws IOException {
    String before = resolver.resolve(probe()).hash();
    Files.writeString(promptFile, "{\"prompt\":\"a\"}\n{\"prompt\":\"c\"}\n");
    assertNotEquals(before, resolver.resolve(probe()).hash());
  }

  @Test
  public void fingerprintMethodIsPartOfProbeIdentity() {
    ProbeResolver full = new ProbeResolver(new FullFileFingerprinter(), prompts);
    assertNotEquals(resolver.resolve(probe()).hash(), full.resolve(probe()).hash());
  }

  @Test(expected = FingerprintException.class)
  public void draftWithoutWeightsFails() throws IOException {
    Files.delete(draftDir.resolve("model.safetensors"));
    resolver.resolve(probe());
  }

  @Test(expected = PromptSetException.class)
  public void missingPromptFileFails() throws IOException {
    Files.delete(promptFile);
    resolver.resolve(probe());
  }
}
