package dev.draftwatch.fingerprint;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * The fingerprint algorithm of SPEC.md F1, shared by both methods; subclasses supply only the
 * per-file content digest (Template Method, so the walk, ordering, and encoding cannot drift
 * apart between methods).
 *
 * <p>Algorithm (DECISIONS.md D23):
 *
 * <ol>
 *   <li>Find every regular file under the directory, recursively and following symbolic links,
 *       whose name ends in {@code .safetensors} or {@code .bin}. Other files, including index
 *       and config JSON, are ignored: they are identical across checkpoints of one run.
 *   <li>Sort them by relative path with {@code /} separators ({@link String#compareTo}).
 *   <li>For each, feed {@code <relative path> NUL <size in bytes> NUL <content digest hex> LF}
 *       (UTF-8) into one SHA-256. NUL cannot occur in a file name, so the encoding is
 *       unambiguous.
 *   <li>The fingerprint is {@code <method>-<hex of that SHA-256>}.
 * </ol>
 */
public abstract class WeightFileFingerprinter implements Fingerprinter {
  private final FingerprintMethod method;

  protected WeightFileFingerprinter(FingerprintMethod method) {
    this.method = method;
  }

  public final FingerprintMethod method() {
    return method;
  }

  @Override
  public final String fingerprint(Path dir) {
    if (!Files.isDirectory(dir)) {
      throw new FingerprintException(dir, "not a directory");
    }
    TreeMap<String, Path> files = weightFiles(dir);
    if (files.isEmpty()) {
      throw new FingerprintException(dir, "no weight files (*.safetensors, *.bin)");
    }
    MessageDigest combined = Sha256.newDigest();
    for (String relative : files.keySet()) {
      Path file = files.get(relative);
      long size = size(file);
      String digest = Sha256.toHex(contentDigest(file, size));
      String entry = relative + '\0' + size + '\0' + digest + '\n';
      combined.update(entry.getBytes(StandardCharsets.UTF_8));
    }
    return method.wireName() + "-" + Sha256.toHex(combined.digest());
  }

  /**
   * SHA-256 of the content of {@code file} as this method defines it.
   *
   * @param size the file's size when listed; implementations must fail if it changes
   * @throws FingerprintException on I/O failure or a size change
   */
  protected abstract byte[] contentDigest(Path file, long size);

  /**
   * Opens {@code file}, lets {@code reader} feed ranges of it into a fresh SHA-256, and checks
   * afterwards that the file still has {@code size} bytes.
   *
   * @throws FingerprintException on I/O failure or if the file changed size
   */
  protected static byte[] digestOf(Path file, long size, RangeReader reader) {
    MessageDigest digest = Sha256.newDigest();
    try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
      reader.read(new Ranges(channel, file, digest));
      if (channel.size() != size) {
        throw new FingerprintException(file, "size changed while reading; still being written?");
      }
    } catch (IOException e) {
      throw new FingerprintException(file, "cannot read: " + e.getMessage(), e);
    }
    return digest.digest();
  }

  /** Feeds chosen byte ranges of one open file into a digest. */
  protected interface RangeReader {
    void read(Ranges ranges) throws IOException;
  }

  /** Positioned reads from one open file into one digest. */
  protected static final class Ranges {
    private static final int BUFFER_BYTES = 1 << 16;

    private final FileChannel channel;
    private final Path file;
    private final MessageDigest digest;
    private final ByteBuffer buffer = ByteBuffer.allocate(BUFFER_BYTES);

    private Ranges(FileChannel channel, Path file, MessageDigest digest) {
      this.channel = channel;
      this.file = file;
      this.digest = digest;
    }

    /**
     * Digests exactly {@code length} bytes starting at {@code offset}.
     *
     * @throws FingerprintException if the file ends early (it shrank while being read)
     */
    public void digest(long offset, long length) throws IOException {
      long position = offset;
      long remaining = length;
      while (remaining > 0) {
        buffer.clear();
        buffer.limit((int) Math.min(buffer.capacity(), remaining));
        int read = channel.read(buffer, position);
        if (read < 0) {
          throw new FingerprintException(file, "ended early; still being written?");
        }
        buffer.flip();
        digest.update(buffer);
        position += read;
        remaining -= read;
      }
    }
  }

  /** True if {@code fileName} is a weight file name per SPEC.md F1. */
  static boolean isWeightFile(String fileName) {
    return fileName.endsWith(".safetensors") || fileName.endsWith(".bin");
  }

  private static TreeMap<String, Path> weightFiles(Path dir) {
    List<Path> found = new ArrayList<>();
    try (Stream<Path> walk = Files.walk(dir, FileVisitOption.FOLLOW_LINKS)) {
      walk.filter(p -> isWeightFile(p.getFileName().toString()))
          .filter(Files::isRegularFile)
          .forEach(found::add);
    } catch (IOException | UncheckedIOException e) {
      throw new FingerprintException(dir, "cannot list files: " + e.getMessage(), e);
    }
    TreeMap<String, Path> byRelativePath = new TreeMap<>();
    for (Path file : found) {
      byRelativePath.put(relativeName(dir.relativize(file)), file);
    }
    return byRelativePath;
  }

  private static String relativeName(Path relative) {
    StringBuilder name = new StringBuilder();
    for (Path part : relative) {
      if (name.length() > 0) {
        name.append('/');
      }
      name.append(part.toString());
    }
    return name.toString();
  }

  private static long size(Path file) {
    try {
      return Files.size(file);
    } catch (IOException e) {
      throw new FingerprintException(file, "cannot read size: " + e.getMessage(), e);
    }
  }
}
