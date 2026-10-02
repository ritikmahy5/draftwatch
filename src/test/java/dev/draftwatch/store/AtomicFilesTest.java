package dev.draftwatch.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class AtomicFilesTest {
  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private long filesIn(Path dir) throws IOException {
    try (Stream<Path> list = Files.list(dir)) {
      return list.count();
    }
  }

  @Test
  public void writeCreatesParentsAndReplaces() throws IOException {
    Path target = tmp.getRoot().toPath().resolve("a/b/file.json");
    AtomicFiles.write(target, "one".getBytes());
    AtomicFiles.write(target, "two".getBytes());
    assertEquals("two", Files.readString(target));
    assertEquals("no temporary files are left behind", 1, filesIn(target.getParent()));
  }

  @Test
  public void writeNewRefusesAnExistingFileAndLeavesItIntact() throws IOException {
    Path target = tmp.getRoot().toPath().resolve("result.json");
    AtomicFiles.writeNew(target, "first".getBytes());
    try {
      AtomicFiles.writeNew(target, "second".getBytes());
      fail("expected FileAlreadyExistsException");
    } catch (FileAlreadyExistsException expected) {
      // expected
    }
    assertEquals("first", Files.readString(target));
    assertEquals(1, filesIn(target.getParent()));
  }
}
