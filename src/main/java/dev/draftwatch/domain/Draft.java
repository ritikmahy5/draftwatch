package dev.draftwatch.domain;

import java.nio.file.Path;
import java.util.Objects;

/** The speculator model a probe measures: an id, a local directory, and a draft structure. */
public final class Draft {
  private final String id;
  private final Path path;
  private final DraftStructure structure;

  private Draft(String id, Path path, DraftStructure structure) {
    this.id = id;
    this.path = path;
    this.structure = structure;
  }

  /**
   * Creates a draft.
   *
   * @param id identifier passed to the harness as {@code --draft-id}; see {@link Names}
   * @param path local directory holding the draft's weights (they are fingerprinted)
   * @param structure draft structure; v1 has only {@link DraftStructure#CHAIN}
   */
  public static Draft of(String id, Path path, DraftStructure structure) {
    return new Draft(
        Names.require(id, "draft id"),
        Require.nonNull(path, "draft path"),
        Require.nonNull(structure, "draft structure"));
  }

  public String id() {
    return id;
  }

  public Path path() {
    return path;
  }

  public DraftStructure structure() {
    return structure;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Draft)) {
      return false;
    }
    Draft that = (Draft) o;
    return id.equals(that.id) && path.equals(that.path) && structure == that.structure;
  }

  @Override
  public int hashCode() {
    return Objects.hash(id, path, structure);
  }

  @Override
  public String toString() {
    return "Draft{" + id + ", " + structure.wireName() + ", " + path + "}";
  }
}
