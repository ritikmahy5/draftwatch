package dev.draftwatch.fingerprint;

import java.util.Objects;
import java.util.Optional;

/** Remembers fingerprints between passes (see {@link CachingFingerprinter}). */
public interface FingerprintCache {
  /** A cached fingerprint and the file signature it was computed for. */
  final class Entry {
    private final String signature;
    private final String fingerprint;

    public Entry(String signature, String fingerprint) {
      this.signature = Objects.requireNonNull(signature, "signature");
      this.fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
    }

    public String signature() {
      return signature;
    }

    public String fingerprint() {
      return fingerprint;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof Entry)) {
        return false;
      }
      Entry that = (Entry) o;
      return signature.equals(that.signature) && fingerprint.equals(that.fingerprint);
    }

    @Override
    public int hashCode() {
      return Objects.hash(signature, fingerprint);
    }
  }

  Optional<Entry> get(String key);

  void put(String key, Entry entry);
}
