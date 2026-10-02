package dev.draftwatch.store;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Atomic file writes (ARCHITECTURE.md, "Persistence"): content goes to a temporary file in the
 * target's directory, is flushed to disk, and is then renamed over the target, so a reader sees
 * either the old file or the complete new one, never a partial write.
 */
public final class AtomicFiles {
  private AtomicFiles() {}

  /** Writes {@code content} to {@code target}, replacing any existing file. */
  public static void write(Path target, byte[] content) throws IOException {
    Path tmp = temp(target, content);
    try {
      Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(tmp);
    }
  }

  /**
   * Writes {@code content} to {@code target}, which must not exist. The existence check and the
   * rename are not one atomic step; callers hold the state lock, which makes them single-writer.
   *
   * @throws FileAlreadyExistsException if {@code target} exists
   */
  public static void writeNew(Path target, byte[] content) throws IOException {
    if (Files.exists(target)) {
      throw new FileAlreadyExistsException(target.toString());
    }
    Path tmp = temp(target, content);
    try {
      Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
    } finally {
      Files.deleteIfExists(tmp);
    }
  }

  private static Path temp(Path target, byte[] content) throws IOException {
    Path dir = target.toAbsolutePath().getParent();
    Files.createDirectories(dir);
    Path tmp = Files.createTempFile(dir, "." + target.getFileName() + ".", ".tmp");
    try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
      ByteBuffer buffer = ByteBuffer.wrap(content);
      while (buffer.hasRemaining()) {
        channel.write(buffer);
      }
      channel.force(true);
    } catch (IOException e) {
      Files.deleteIfExists(tmp);
      throw e;
    }
    return tmp;
  }
}
