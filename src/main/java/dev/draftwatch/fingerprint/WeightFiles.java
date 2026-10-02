package dev.draftwatch.fingerprint;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * The weight files of a directory (SPEC.md F1): every regular file whose name ends in
 * {@code .safetensors} or {@code .bin}, found recursively following symbolic links, keyed by
 * {@code /}-separated relative path in sorted order. Shared by the fingerprinters and the cache
 * so both see exactly the same files.
 */
final class WeightFiles {
  private WeightFiles() {}

  static boolean isWeightFile(String fileName) {
    return fileName.endsWith(".safetensors") || fileName.endsWith(".bin");
  }

  /** @throws FingerprintException if the directory cannot be listed */
  static TreeMap<String, Path> under(Path dir) {
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
}
