package dev.draftwatch.fingerprint;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.draftwatch.store.FileFingerprintCache;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class CachingFingerprinterTest {
  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private final SampledBlockFingerprinter real = new SampledBlockFingerprinter();
  private int computed;
  private final Fingerprinter counting =
      dir -> {
        computed++;
        return real.fingerprint(dir);
      };
  private Path state;
  private Path ckpt;

  @Before
  public void setUp() throws IOException {
    state = tmp.newFolder("state").toPath();
    ckpt = tmp.newFolder("checkpoint-100").toPath();
    Files.write(ckpt.resolve("model.safetensors"), new byte[] {1, 2, 3});
    Files.writeString(ckpt.resolve("config.json"), "{}");
  }

  private CachingFingerprinter caching(FingerprintMethod method) {
    return new CachingFingerprinter(
        counting, method, new FileFingerprintCache(state, new ObjectMapper()));
  }

  @Test
  public void unchangedDirectoryIsFingerprintedOnceAcrossInstances() {
    String first = caching(FingerprintMethod.SAMPLED).fingerprint(ckpt);
    String second = caching(FingerprintMethod.SAMPLED).fingerprint(ckpt);
    assertEquals(real.fingerprint(ckpt), first);
    assertEquals(first, second);
    assertEquals(1, computed);
  }

  @Test
  public void changedWeightFileIsFingerprintedAgain() throws IOException {
    CachingFingerprinter f = caching(FingerprintMethod.SAMPLED);
    String before = f.fingerprint(ckpt);
    Files.write(ckpt.resolve("model.safetensors"), new byte[] {1, 2, 4});
    Files.setLastModifiedTime(
        ckpt.resolve("model.safetensors"), FileTime.from(Instant.parse("2030-01-01T00:00:00Z")));
    String after = f.fingerprint(ckpt);
    assertEquals(2, computed);
    assertEquals(real.fingerprint(ckpt), after);
    assertNotEquals(before, after);
  }

  @Test
  public void addedWeightFileIsFingerprintedAgainButOtherFilesAreIgnored() throws IOException {
    CachingFingerprinter f = caching(FingerprintMethod.SAMPLED);
    f.fingerprint(ckpt);
    Files.writeString(ckpt.resolve("trainer_state.json"), "{\"global_step\": 100}");
    f.fingerprint(ckpt);
    assertEquals("non-weight files do not change the fingerprint", 1, computed);
    Files.write(ckpt.resolve("adapter_model.bin"), new byte[] {9});
    f.fingerprint(ckpt);
    assertEquals(2, computed);
  }

  @Test
  public void cachedResultOfOneMethodIsNotUsedForAnother() {
    caching(FingerprintMethod.SAMPLED).fingerprint(ckpt);
    caching(FingerprintMethod.FULL).fingerprint(ckpt);
    assertEquals(2, computed);
  }

  @Test(expected = FingerprintException.class)
  public void missingDirectoryStillFailsLoudly() {
    caching(FingerprintMethod.SAMPLED).fingerprint(ckpt.resolve("absent"));
  }
}
