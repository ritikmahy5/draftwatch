package dev.draftwatch.harness;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.draftwatch.domain.PromptSet;
import dev.draftwatch.fingerprint.Sha256;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Reads a prompt file and checks it against MEASUREMENT_CONTRACT.md, "Prompt file"
 * (DECISIONS.md D26).
 *
 * <ul>
 *   <li>The file is UTF-8 without a byte-order mark. Lines are separated by LF; a trailing CR
 *       belongs to the line.
 *   <li>A line is empty if it consists only of space, tab, and CR (JSON whitespace other than
 *       LF). Empty lines are skipped and do not count.
 *   <li>Every non-empty line is exactly one JSON object. The k-th non-empty line (0-based) is
 *       the prompt with {@code prompt_index} k.
 *   <li>There is at least one prompt.
 *   <li>{@code prompt_set_sha256} is the SHA-256 of the file's exact bytes.
 * </ul>
 */
public final class PromptSetReader {
  private final ObjectMapper json;

  public PromptSetReader(ObjectMapper json) {
    this.json =
        Objects.requireNonNull(json, "json")
            .copy()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  /**
   * Reads and checks {@code file}.
   *
   * @throws PromptSetException naming the file, and the line where relevant
   */
  public PromptSet read(Path file) {
    byte[] bytes;
    try {
      bytes = Files.readAllBytes(file);
    } catch (IOException e) {
      throw new PromptSetException(file, "cannot read prompt file: " + e.getMessage(), e);
    }
    String text = decodeUtf8(file, bytes);
    if (text.startsWith("﻿")) {
      throw new PromptSetException(file, "starts with a UTF-8 byte-order mark; remove it");
    }
    String[] lines = text.split("\n", -1);
    int prompts = 0;
    for (int i = 0; i < lines.length; i++) {
      if (isEmpty(lines[i])) {
        continue;
      }
      requireObject(file, i + 1, lines[i]);
      prompts++;
    }
    if (prompts == 0) {
      throw new PromptSetException(file, "contains no prompts");
    }
    return PromptSet.of(file, Sha256.hex(bytes), prompts);
  }

  /** True if {@code line} has only space, tab, and CR. */
  static boolean isEmpty(String line) {
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (c != ' ' && c != '\t' && c != '\r') {
        return false;
      }
    }
    return true;
  }

  private static String decodeUtf8(Path file, byte[] bytes) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException e) {
      throw new PromptSetException(file, "is not valid UTF-8", e);
    }
  }

  private void requireObject(Path file, int lineNumber, String line) {
    JsonNode node;
    try {
      node = json.readTree(line);
    } catch (JsonProcessingException e) {
      throw new PromptSetException(
          file, "line " + lineNumber + " is not valid JSON: " + e.getOriginalMessage(), e);
    }
    if (node == null || !node.isObject()) {
      throw new PromptSetException(file, "line " + lineNumber + " is not a JSON object");
    }
  }
}
