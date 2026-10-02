package dev.draftwatch.exec.slurm;

import java.util.List;
import java.util.stream.Collectors;

/** Single-quoting for POSIX {@code sh}, used in every generated script. */
public final class ShellQuote {
  private ShellQuote() {}

  /** {@code it's} becomes {@code 'it'\''s'}: inside single quotes nothing is special. */
  public static String quote(String text) {
    return "'" + text.replace("'", "'\\''") + "'";
  }

  /** Each element quoted, joined by spaces. */
  public static String join(List<String> words) {
    return words.stream().map(ShellQuote::quote).collect(Collectors.joining(" "));
  }
}
