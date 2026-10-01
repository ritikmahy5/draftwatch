package dev.draftwatch.config;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Reads {@code draftwatch.yaml} and validates it.
 *
 * <p>YAML is read strictly: a key repeated in one mapping is an error (otherwise the last one
 * would silently win), floating-point numbers keep their exact decimal value, and a second YAML
 * document in the file is an error.
 */
public final class ConfigLoader {
  public static final String DEFAULT_FILE_NAME = "draftwatch.yaml";

  private final ObjectMapper yaml;
  private final ConfigValidator validator;

  public ConfigLoader(ConfigValidator validator) {
    this.validator = Objects.requireNonNull(validator, "validator");
    this.yaml =
        YAMLMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
  }

  /**
   * Loads and validates {@code file}.
   *
   * @throws ConfigException if the file cannot be read, is not valid YAML, or fails validation
   */
  public DraftwatchConfig load(Path file) {
    Path absolute = file.toAbsolutePath().normalize();
    return validator.validate(parse(absolute), absolute);
  }

  private JsonNode parse(Path file) {
    if (!Files.exists(file)) {
      throw failure(file, "file not found; create one with 'draftwatch init'");
    }
    try {
      return yaml.readTree(file.toFile());
    } catch (JsonProcessingException e) {
      JsonLocation where = e.getLocation();
      String position =
          where == null ? "" : " at line " + where.getLineNr() + ", column " + where.getColumnNr();
      throw failure(file, "invalid YAML" + position + ": " + e.getOriginalMessage());
    } catch (IOException e) {
      throw failure(file, "cannot read: " + e.getMessage());
    }
  }

  private static ConfigException failure(Path file, String message) {
    return new ConfigException(file, List.of(ConfigError.of(ConfigNode.ROOT, message)));
  }
}
