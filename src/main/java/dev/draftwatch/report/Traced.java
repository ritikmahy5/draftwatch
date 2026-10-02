package dev.draftwatch.report;

import java.nio.file.Path;
import java.util.Objects;

/**
 * A value the report shows, with where it is stored: a result file and the JSON Pointer of the
 * value in it. Rendered as a link to {@code file#pointer} (DECISIONS.md D68).
 */
public final class Traced {
  private final String text;
  private final Path file;
  private final String pointer;

  private Traced(String text, Path file, String pointer) {
    this.text = text;
    this.file = file;
    this.pointer = pointer;
  }

  public static Traced of(String text, Path file, String pointer) {
    return new Traced(
        Objects.requireNonNull(text, "text"),
        Objects.requireNonNull(file, "file"),
        Objects.requireNonNull(pointer, "pointer"));
  }

  /** A number exactly as stored: {@code Double.toString}, which reads back to the same value. */
  public static Traced number(double value, Path file, String pointer) {
    return of(Double.toString(value), file, pointer);
  }

  public static Traced number(long value, Path file, String pointer) {
    return of(Long.toString(value), file, pointer);
  }

  public String text() {
    return text;
  }

  public Path file() {
    return file;
  }

  public String pointer() {
    return pointer;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Traced)) {
      return false;
    }
    Traced that = (Traced) o;
    return text.equals(that.text) && file.equals(that.file) && pointer.equals(that.pointer);
  }

  @Override
  public int hashCode() {
    return Objects.hash(text, file, pointer);
  }

  @Override
  public String toString() {
    return text + " (" + file + "#" + pointer + ")";
  }
}
