package dev.draftwatch.discovery;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Determines a checkpoint's training step (SPEC.md F1, "Step extraction").
 *
 * <ol>
 *   <li>If {@code trainer_state.json} exists in the directory, its integer {@code global_step}
 *       is the step. This is the file and field Hugging Face {@code Trainer} writes
 *       ({@code TRAINER_STATE_NAME}, {@code TrainerState.global_step}). If the file exists but
 *       is unreadable, malformed, or lacks a non-negative integer {@code global_step}, that is
 *       an error, not a reason to fall back (DECISIONS.md D27).
 *   <li>Otherwise group 1 of the target's {@code step_regex}, searched for in the directory
 *       name, is the step.
 *   <li>Otherwise the checkpoint is rejected.
 * </ol>
 */
public final class StepExtractor {
  public static final String TRAINER_STATE_FILE = "trainer_state.json";

  private final Pattern stepRegex;
  private final ObjectMapper json;

  /**
   * Creates an extractor.
   *
   * @param stepRegex pattern with at least one capture group; group 1 is the step
   * @param json mapper used to read {@code trainer_state.json}
   */
  public StepExtractor(Pattern stepRegex, ObjectMapper json) {
    this.stepRegex = Objects.requireNonNull(stepRegex, "stepRegex");
    this.json = Objects.requireNonNull(json, "json");
    if (stepRegex.matcher("").groupCount() < 1) {
      throw new IllegalArgumentException("step_regex must have a capture group: " + stepRegex);
    }
  }

  /**
   * The training step of the checkpoint in {@code checkpointDir}.
   *
   * @throws StepExtractionException naming the directory if no step can be determined
   */
  public long extract(Path checkpointDir) {
    Path trainerState = checkpointDir.resolve(TRAINER_STATE_FILE);
    if (Files.exists(trainerState)) {
      return fromTrainerState(checkpointDir, trainerState);
    }
    return fromDirectoryName(checkpointDir);
  }

  private long fromTrainerState(Path checkpointDir, Path trainerState) {
    JsonNode root;
    try {
      root = json.readTree(trainerState.toFile());
    } catch (JsonProcessingException e) {
      throw new StepExtractionException(
          checkpointDir, TRAINER_STATE_FILE + " is not valid JSON: " + e.getOriginalMessage(), e);
    } catch (IOException e) {
      throw new StepExtractionException(
          checkpointDir, "cannot read " + TRAINER_STATE_FILE + ": " + e.getMessage(), e);
    }
    JsonNode step = root == null ? null : root.get("global_step");
    if (step == null || !step.isIntegralNumber() || !step.canConvertToLong()) {
      throw new StepExtractionException(
          checkpointDir, TRAINER_STATE_FILE + " has no integer global_step");
    }
    if (step.longValue() < 0) {
      throw new StepExtractionException(
          checkpointDir, TRAINER_STATE_FILE + " global_step is negative: " + step.longValue());
    }
    return step.longValue();
  }

  private long fromDirectoryName(Path checkpointDir) {
    Path fileName = checkpointDir.getFileName();
    String name = fileName == null ? "" : fileName.toString();
    Matcher m = stepRegex.matcher(name);
    if (!m.find() || m.group(1) == null) {
      throw new StepExtractionException(
          checkpointDir,
          "no "
              + TRAINER_STATE_FILE
              + " and directory name '"
              + name
              + "' does not match step_regex "
              + stepRegex.pattern());
    }
    String digits = m.group(1);
    try {
      long step = Long.parseLong(digits);
      if (step < 0) {
        throw new NumberFormatException("negative");
      }
      return step;
    } catch (NumberFormatException e) {
      throw new StepExtractionException(
          checkpointDir,
          "step_regex group 1 '" + digits + "' is not a non-negative integer",
          e);
    }
  }
}
