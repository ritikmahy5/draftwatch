package dev.draftwatch.fingerprint;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Reuses a directory's fingerprint while its weight files are unchanged (Decorator: adds caching
 * to any fingerprinter without changing it; DECISIONS.md D53). A {@code watch} pass would
 * otherwise re-read every checkpoint's sampled blocks, and every base model's, on every pass.
 *
 * <p>"Unchanged" means the same signature: the SHA-256 of every weight file's relative path,
 * size, and modification time. The cache key includes the method, so a {@code sampled} result is
 * never returned for {@code full}. A result is stored only if the signature is the same after
 * hashing as before, so a checkpoint still being written is never cached.
 */
public final class CachingFingerprinter implements Fingerprinter {
  private final Fingerprinter delegate;
  private final FingerprintMethod method;
  private final FingerprintCache cache;

  public CachingFingerprinter(
      Fingerprinter delegate, FingerprintMethod method, FingerprintCache cache) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    this.method = Objects.requireNonNull(method, "method");
    this.cache = Objects.requireNonNull(cache, "cache");
  }

  @Override
  public String fingerprint(Path dir) {
    if (!Files.isDirectory(dir)) {
      return delegate.fingerprint(dir); // reports the problem
    }
    String key = method.wireName() + ":" + dir.toAbsolutePath().normalize();
    String before = signature(dir);
    Optional<FingerprintCache.Entry> cached = cache.get(key);
    if (cached.isPresent() && cached.get().signature().equals(before)) {
      return cached.get().fingerprint();
    }
    String fingerprint = delegate.fingerprint(dir);
    if (signature(dir).equals(before)) {
      cache.put(key, new FingerprintCache.Entry(before, fingerprint));
    }
    return fingerprint;
  }

  /** SHA-256 of {@code <path> NUL <size> NUL <mtime> LF} for every weight file, in order. */
  static String signature(Path dir) {
    MessageDigest digest = Sha256.newDigest();
    for (Map.Entry<String, Path> file : WeightFiles.under(dir).entrySet()) {
      try {
        String line =
            file.getKey() + '\0' + Files.size(file.getValue()) + '\0'
                + Files.getLastModifiedTime(file.getValue()) + '\n';
        digest.update(line.getBytes(StandardCharsets.UTF_8));
      } catch (IOException e) {
        throw new FingerprintException(file.getValue(), "cannot stat: " + e.getMessage(), e);
      }
    }
    return Sha256.toHex(digest.digest());
  }
}
