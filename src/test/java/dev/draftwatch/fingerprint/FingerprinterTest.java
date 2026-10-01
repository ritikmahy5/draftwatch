package dev.draftwatch.fingerprint;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class FingerprinterTest {
  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private static final List<WeightFileFingerprinter> BOTH =
      Arrays.asList(new SampledBlockFingerprinter(), new FullFileFingerprinter());

  private static final String INDEX =
      "{\"weight_map\":{\"w\":\"model-00001-of-00001.safetensors\"}}";
  private static final String CONFIG = "{\"model_type\":\"llama\"}";

  /** A checkpoint directory with fixed index/config files and the given shard bytes. */
  private Path checkpoint(String name, byte[] shard) throws IOException {
    Path dir = tmp.newFolder(name).toPath();
    Files.writeString(dir.resolve("model.safetensors.index.json"), INDEX);
    Files.writeString(dir.resolve("config.json"), CONFIG);
    Files.write(dir.resolve("model-00001-of-00001.safetensors"), shard);
    return dir;
  }

  private static byte[] bytes(int length, int seed) {
    byte[] data = new byte[length];
    for (int i = 0; i < length; i++) {
      data[i] = (byte) (i * 31 + seed);
    }
    return data;
  }

  private Path copyOf(Path source, String name) throws IOException {
    Path target = tmp.getRoot().toPath().resolve(name);
    try (Stream<Path> walk = Files.walk(source)) {
      for (Path p : (Iterable<Path>) walk::iterator) {
        Files.copy(p, target.resolve(source.relativize(p).toString()));
      }
    }
    return target;
  }

  private static void flipByte(Path file, long offset) throws IOException {
    try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
      raf.seek(offset);
      int b = raf.read();
      raf.seek(offset);
      raf.write(b ^ 0xff);
    }
  }

  // --- ROADMAP M1 "done when" -----------------------------------------------------------------

  @Test
  public void identicalMetadataButDifferentWeightsGiveDifferentFingerprints() throws IOException {
    Path a = checkpoint("checkpoint-100", bytes(4096, 1));
    Path b = checkpoint("checkpoint-200", bytes(4096, 2));
    for (Fingerprinter f : BOTH) {
      assertNotEquals(f.getClass().getSimpleName(), f.fingerprint(a), f.fingerprint(b));
    }
  }

  @Test
  public void copiedCheckpointHasSameFingerprintAsSource() throws IOException {
    Path source = checkpoint("checkpoint-100", bytes(4096, 1));
    Path copy = copyOf(source, "elsewhere-renamed");
    for (Fingerprinter f : BOTH) {
      assertEquals(f.getClass().getSimpleName(), f.fingerprint(source), f.fingerprint(copy));
    }
  }

  // --- format and scope ------------------------------------------------------------------------

  @Test
  public void fingerprintIsMethodPrefixPlusHex() throws IOException {
    Path dir = checkpoint("c", bytes(100, 1));
    String sampled = new SampledBlockFingerprinter().fingerprint(dir);
    String full = new FullFileFingerprinter().fingerprint(dir);
    assertTrue(sampled, sampled.matches("sampled-[0-9a-f]{64}"));
    assertTrue(full, full.matches("full-[0-9a-f]{64}"));
    assertEquals(
        "a file below the sampling threshold is hashed whole by both methods",
        sampled.substring("sampled-".length()),
        full.substring("full-".length()));
  }

  @Test
  public void sampledFingerprintMatchesDocumentedEncodingExactly() throws IOException {
    // Geometry 3 blocks of 4 bytes over a 20-byte file: offsets 0, 8, 16.
    SampledBlockFingerprinter f = new SampledBlockFingerprinter(4, 3);
    byte[] content = bytes(20, 7);
    Path dir = tmp.newFolder("enc").toPath();
    Files.write(dir.resolve("w.bin"), content);

    MessageDigest blocks = Sha256.newDigest();
    blocks.update(content, 0, 4);
    blocks.update(content, 8, 4);
    blocks.update(content, 16, 4);
    String entry = "w.bin\0" + "20\0" + Sha256.toHex(blocks.digest()) + "\n";
    String expected = "sampled-" + Sha256.hex(entry.getBytes(StandardCharsets.UTF_8));

    assertEquals(expected, f.fingerprint(dir));
  }

  @Test
  public void nonWeightFilesAreIgnored() throws IOException {
    Path dir = checkpoint("c", bytes(4096, 1));
    String before = new FullFileFingerprinter().fingerprint(dir);
    Files.writeString(dir.resolve("config.json"), "{\"model_type\":\"changed\"}");
    Files.writeString(dir.resolve("trainer_state.json"), "{\"global_step\":5}");
    Files.writeString(dir.resolve("optimizer.pt"), "not a weight file");
    assertEquals(before, new FullFileFingerprinter().fingerprint(dir));
  }

  @Test
  public void binFilesAndNestedFilesAreIncludedWithSlashPaths() throws IOException {
    Path dir = tmp.newFolder("nested").toPath();
    Files.createDirectories(dir.resolve("sub"));
    Files.write(dir.resolve("sub").resolve("pytorch_model.bin"), bytes(10, 3));
    FullFileFingerprinter f = new FullFileFingerprinter();
    String digest = Sha256.hex(bytes(10, 3));
    String entry = "sub/pytorch_model.bin\0" + "10\0" + digest + "\n";
    assertEquals("full-" + Sha256.hex(entry.getBytes(StandardCharsets.UTF_8)), f.fingerprint(dir));
  }

  @Test
  public void symlinkedWeightsFingerprintLikeTheFilesTheyPointTo() throws IOException {
    // Hugging Face hub cache layout: snapshots/<rev>/<name> -> ../../blobs/<hash>.
    Path repo = tmp.newFolder("models--org--draft").toPath();
    Path blobs = Files.createDirectories(repo.resolve("blobs"));
    Path snapshot = Files.createDirectories(repo.resolve("snapshots").resolve("abc123"));
    Files.write(blobs.resolve("deadbeef"), bytes(4096, 9));
    Files.createSymbolicLink(
        snapshot.resolve("model.safetensors"), Paths.get("../../blobs/deadbeef"));

    Path plain = tmp.newFolder("plain").toPath();
    Files.write(plain.resolve("model.safetensors"), bytes(4096, 9));

    for (Fingerprinter f : BOTH) {
      assertEquals(f.getClass().getSimpleName(), f.fingerprint(plain), f.fingerprint(snapshot));
    }
  }

  @Test
  public void renamingAWeightFileChangesFingerprint() throws IOException {
    Path dir = checkpoint("c", bytes(4096, 1));
    String before = new FullFileFingerprinter().fingerprint(dir);
    Files.move(
        dir.resolve("model-00001-of-00001.safetensors"), dir.resolve("model.safetensors"));
    assertNotEquals(before, new FullFileFingerprinter().fingerprint(dir));
  }

  @Test
  public void directoryWithoutWeightFilesIsRejected() throws IOException {
    Path dir = tmp.newFolder("empty").toPath();
    Files.writeString(dir.resolve("model.safetensors.index.json"), INDEX);
    for (Fingerprinter f : BOTH) {
      try {
        f.fingerprint(dir);
        fail("expected FingerprintException");
      } catch (FingerprintException e) {
        assertEquals(dir, e.path());
        assertTrue(e.getMessage(), e.getMessage().contains("no weight files"));
      }
    }
  }

  @Test
  public void missingDirectoryIsRejected() {
    Path missing = tmp.getRoot().toPath().resolve("absent");
    for (Fingerprinter f : BOTH) {
      try {
        f.fingerprint(missing);
        fail("expected FingerprintException");
      } catch (FingerprintException e) {
        assertTrue(e.getMessage(), e.getMessage().contains("not a directory"));
      }
    }
  }

  // --- sampling at the real geometry ---------------------------------------------------------

  /** A 9 MiB shard: larger than 8 × 1 MiB, so it is sampled rather than hashed whole. */
  private Path nineMebibyteCheckpoint() throws IOException {
    return checkpoint("big", bytes(9 * SampledBlockFingerprinter.BLOCK_BYTES, 5));
  }

  @Test
  public void sampledDetectsChangeInFinalMebibyte() throws IOException {
    Path dir = nineMebibyteCheckpoint();
    Path shard = dir.resolve("model-00001-of-00001.safetensors");
    SampledBlockFingerprinter f = new SampledBlockFingerprinter();
    String before = f.fingerprint(dir);
    flipByte(shard, Files.size(shard) - 1);
    assertNotEquals(before, f.fingerprint(dir));
  }

  @Test
  public void sampledMissesChangeBetweenBlocksButFullDetectsIt() throws IOException {
    Path dir = nineMebibyteCheckpoint();
    Path shard = dir.resolve("model-00001-of-00001.safetensors");
    long size = Files.size(shard);
    SampledBlockFingerprinter sampled = new SampledBlockFingerprinter();
    long endOfBlock0 = sampled.blockOffset(0, size) + SampledBlockFingerprinter.BLOCK_BYTES;
    long startOfBlock1 = sampled.blockOffset(1, size);
    assertTrue("blocks 0 and 1 leave a gap", endOfBlock0 < startOfBlock1);

    String sampledBefore = sampled.fingerprint(dir);
    String fullBefore = new FullFileFingerprinter().fingerprint(dir);
    flipByte(shard, endOfBlock0);
    assertEquals(sampledBefore, sampled.fingerprint(dir));
    assertNotEquals(fullBefore, new FullFileFingerprinter().fingerprint(dir));
  }

  @Test
  public void sampledBlocksStartAtZeroAndEndAtFinalByte() {
    SampledBlockFingerprinter f = new SampledBlockFingerprinter();
    long size = 5_000_000_000L;
    assertEquals(0, f.blockOffset(0, size));
    assertEquals(
        size - SampledBlockFingerprinter.BLOCK_BYTES,
        f.blockOffset(SampledBlockFingerprinter.BLOCK_COUNT - 1, size));
  }

  @Test
  public void sizeChangeIsDetectedEvenOutsideSampledBlocks() throws IOException {
    Path dir = nineMebibyteCheckpoint();
    Path shard = dir.resolve("model-00001-of-00001.safetensors");
    SampledBlockFingerprinter f = new SampledBlockFingerprinter();
    String before = f.fingerprint(dir);
    try (RandomAccessFile raf = new RandomAccessFile(shard.toFile(), "rw")) {
      raf.setLength(raf.length() + 1);
    }
    assertNotEquals(before, f.fingerprint(dir));
  }
}
