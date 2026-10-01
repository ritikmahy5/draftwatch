package dev.draftwatch.domain;

import java.nio.file.Path;
import java.util.Objects;

/**
 * A prompt file as the engine sees it: its location, the SHA-256 of its exact bytes, and its
 * number of prompts (non-empty lines, as defined in MEASUREMENT_CONTRACT.md, "Prompt file").
 */
public final class PromptSet {
  private final Path path;
  private final String sha256;
  private final int promptCount;

  private PromptSet(Path path, String sha256, int promptCount) {
    this.path = path;
    this.sha256 = sha256;
    this.promptCount = promptCount;
  }

  public static PromptSet of(Path path, String sha256, int promptCount) {
    return new PromptSet(
        Require.nonNull(path, "prompts path"),
        Require.sha256Hex(sha256, "prompt_set_sha256"),
        Require.positive(promptCount, "prompt count"));
  }

  public Path path() {
    return path;
  }

  /** Lowercase hex SHA-256 of the file's bytes. */
  public String sha256() {
    return sha256;
  }

  public int promptCount() {
    return promptCount;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof PromptSet)) {
      return false;
    }
    PromptSet that = (PromptSet) o;
    return path.equals(that.path) && sha256.equals(that.sha256) && promptCount == that.promptCount;
  }

  @Override
  public int hashCode() {
    return Objects.hash(path, sha256, promptCount);
  }

  @Override
  public String toString() {
    return "PromptSet{" + path + ", " + promptCount + " prompts, sha256=" + sha256 + "}";
  }
}
