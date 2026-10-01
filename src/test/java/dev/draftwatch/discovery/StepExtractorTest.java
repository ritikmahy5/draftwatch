package dev.draftwatch.discovery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.draftwatch.domain.Target;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class StepExtractorTest {
  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private final StepExtractor defaults =
      new StepExtractor(Pattern.compile(Target.DEFAULT_STEP_REGEX), new ObjectMapper());

  private Path dir(String name) throws IOException {
    return tmp.newFolder(name).toPath();
  }

  private Path dirWithTrainerState(String name, String json) throws IOException {
    Path d = dir(name);
    Files.writeString(d.resolve(StepExtractor.TRAINER_STATE_FILE), json);
    return d;
  }

  private static void assertRejected(StepExtractor extractor, Path dir, String fragment) {
    try {
      extractor.extract(dir);
      fail("expected StepExtractionException containing: " + fragment);
    } catch (StepExtractionException e) {
      assertEquals(dir, e.checkpointDir());
      assertTrue(e.getMessage(), e.getMessage().startsWith(dir.toString()));
      assertTrue(e.getMessage(), e.getMessage().contains(fragment));
    }
  }

  @Test
  public void readsGlobalStepFromTrainerState() throws IOException {
    Path d = dirWithTrainerState("anything", "{\"epoch\": 1.5, \"global_step\": 1250}");
    assertEquals(1250, defaults.extract(d));
  }

  @Test
  public void trainerStateWinsOverDirectoryName() throws IOException {
    Path d = dirWithTrainerState("checkpoint-500", "{\"global_step\": 499}");
    assertEquals(499, defaults.extract(d));
  }

  @Test
  public void fallsBackToDefaultRegexOnDirectoryName() throws IOException {
    assertEquals(3000, defaults.extract(dir("checkpoint-3000")));
  }

  @Test
  public void defaultRegexIsAnchoredAtEnd() throws IOException {
    assertRejected(defaults, dir("checkpoint-3000-tmp"), "does not match step_regex");
  }

  @Test
  public void customRegexUsesGroupOne() throws IOException {
    StepExtractor custom =
        new StepExtractor(Pattern.compile("^(?:run)_step(\\d+)$"), new ObjectMapper());
    assertEquals(42, custom.extract(dir("run_step42")));
  }

  @Test
  public void rejectsWhenNeitherSourceGivesAStep() throws IOException {
    assertRejected(defaults, dir("final-model"), "no trainer_state.json");
  }

  @Test
  public void malformedTrainerStateIsAnErrorNotAFallback() throws IOException {
    Path d = dirWithTrainerState("checkpoint-100", "{\"global_step\": 1");
    assertRejected(defaults, d, "trainer_state.json is not valid JSON");
  }

  @Test
  public void trainerStateWithoutIntegerGlobalStepIsAnError() throws IOException {
    assertRejected(
        defaults, dirWithTrainerState("checkpoint-1", "{\"epoch\": 1}"), "no integer global_step");
    assertRejected(
        defaults,
        dirWithTrainerState("checkpoint-2", "{\"global_step\": 2.5}"),
        "no integer global_step");
    assertRejected(
        defaults,
        dirWithTrainerState("checkpoint-3", "{\"global_step\": \"3\"}"),
        "no integer global_step");
    assertRejected(
        defaults, dirWithTrainerState("checkpoint-4", "[]"), "no integer global_step");
  }

  @Test
  public void negativeGlobalStepIsAnError() throws IOException {
    assertRejected(
        defaults, dirWithTrainerState("checkpoint-5", "{\"global_step\": -1}"), "negative");
  }

  @Test
  public void nonNumericGroupIsAnError() throws IOException {
    StepExtractor words = new StepExtractor(Pattern.compile("ckpt-(\\w+)$"), new ObjectMapper());
    assertRejected(words, dir("ckpt-abc"), "is not a non-negative integer");
  }

  @Test(expected = IllegalArgumentException.class)
  public void regexWithoutGroupIsRejected() {
    new StepExtractor(Pattern.compile("checkpoint-\\d+"), new ObjectMapper());
  }
}
