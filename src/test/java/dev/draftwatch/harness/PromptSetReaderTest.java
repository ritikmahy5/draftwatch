package dev.draftwatch.harness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.draftwatch.domain.PromptSet;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class PromptSetReaderTest {
  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private final PromptSetReader reader = new PromptSetReader(new ObjectMapper());

  private Path file(String content) throws IOException {
    Path p = tmp.newFile().toPath();
    Files.write(p, content.getBytes(StandardCharsets.UTF_8));
    return p;
  }

  private void assertRejected(String content, String fragment) throws IOException {
    Path p = file(content);
    try {
      reader.read(p);
      fail("expected PromptSetException containing: " + fragment);
    } catch (PromptSetException e) {
      assertEquals(p, e.file());
      assertTrue(e.getMessage(), e.getMessage().contains(fragment));
    }
  }

  @Test
  public void countsNonEmptyLinesAndHashesExactBytes() throws IOException {
    // SHA-256 computed independently with `shasum -a 256` over these exact bytes.
    Path p = file("{\"prompt\":\"a\"}\n\n  \t\r\n{\"prompt\":\"b\"}\r\n");
    PromptSet set = reader.read(p);
    assertEquals(2, set.promptCount());
    assertEquals("f39950a79de7413fbc6fe160d85113e96d46bd5ad0e8b89aea45144b3df3ccb9", set.sha256());
    assertEquals(p, set.path());
  }

  @Test
  public void lastLineWithoutNewlineCounts() throws IOException {
    assertEquals(3, reader.read(file("{}\n{}\n{}")).promptCount());
  }

  @Test
  public void emptyLineMeansOnlySpaceTabCr() {
    assertTrue(PromptSetReader.isEmpty(""));
    assertTrue(PromptSetReader.isEmpty(" \t\r"));
    assertFalse(PromptSetReader.isEmpty(" "));
    assertFalse(PromptSetReader.isEmpty("\f"));
  }

  @Test
  public void rejectsNonObjectLineWithLineNumber() throws IOException {
    assertRejected("{}\n\n[1,2]\n", "line 3 is not a JSON object");
    assertRejected("\"text\"\n", "line 1 is not a JSON object");
  }

  @Test
  public void rejectsInvalidJsonLineWithLineNumber() throws IOException {
    assertRejected("{}\n{\"prompt\": \n", "line 2 is not valid JSON");
  }

  @Test
  public void rejectsTwoObjectsOnOneLine() throws IOException {
    assertRejected("{} {}\n", "line 1 is not valid JSON");
  }

  @Test
  public void rejectsNonBreakingSpaceOnlyLine() throws IOException {
    assertRejected("{}\n \n", "line 2 is not valid JSON");
  }

  @Test
  public void rejectsFileWithoutPrompts() throws IOException {
    assertRejected("\n \n\t\n", "contains no prompts");
  }

  @Test
  public void rejectsInvalidUtf8() throws IOException {
    Path p = tmp.newFile().toPath();
    Files.write(p, new byte[] {'{', '"', 'a', '"', ':', '"', (byte) 0xc3, '"', '}', '\n'});
    try {
      reader.read(p);
      fail("expected PromptSetException");
    } catch (PromptSetException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("not valid UTF-8"));
    }
  }

  @Test
  public void rejectsByteOrderMark() throws IOException {
    assertRejected("\ufeff{}\n", "byte-order mark");
  }

  @Test
  public void rejectsMissingFile() {
    Path missing = tmp.getRoot().toPath().resolve("absent.jsonl");
    try {
      reader.read(missing);
      fail("expected PromptSetException");
    } catch (PromptSetException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("cannot read prompt file"));
    }
  }
}
