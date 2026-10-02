package dev.draftwatch.exec;

/** Source of new job ids. An interface so tests get predictable ids. */
public interface JobIds {
  /**
   * A new id, unique within one state directory and safe as a file name (letters, digits,
   * {@code -}).
   */
  String next();
}
