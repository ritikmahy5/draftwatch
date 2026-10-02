package dev.draftwatch.store;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Strict typed reads from a stored JSON object; a missing or mistyped field throws
 * {@link IllegalArgumentException} naming the field, which the repository reports with the file.
 */
final class Fields {
  private final JsonNode node;
  private final String path;

  Fields(JsonNode node, String path) {
    if (node == null || !node.isObject()) {
      String what = path.isEmpty() ? "document" : path;
      throw new IllegalArgumentException(what + " is not an object");
    }
    this.node = node;
    this.path = path;
  }

  private String at(String key) {
    return path.isEmpty() ? key : path + "." + key;
  }

  private JsonNode required(String key) {
    JsonNode v = node.get(key);
    if (v == null || v.isNull()) {
      throw new IllegalArgumentException(at(key) + " is missing");
    }
    return v;
  }

  Fields object(String key) {
    return new Fields(required(key), at(key));
  }

  Optional<Fields> optionalObject(String key) {
    JsonNode v = node.get(key);
    return v == null || v.isNull() ? Optional.empty() : Optional.of(new Fields(v, at(key)));
  }

  JsonNode raw(String key) {
    return required(key);
  }

  String text(String key) {
    JsonNode v = required(key);
    if (!v.isTextual()) {
      throw new IllegalArgumentException(at(key) + " is not a string");
    }
    return v.textValue();
  }

  Optional<String> optionalText(String key) {
    JsonNode v = node.get(key);
    return v == null || v.isNull() ? Optional.empty() : Optional.of(text(key));
  }

  long longValue(String key) {
    JsonNode v = required(key);
    if (!v.isIntegralNumber() || !v.canConvertToLong()) {
      throw new IllegalArgumentException(at(key) + " is not an integer");
    }
    return v.longValue();
  }

  int intValue(String key) {
    JsonNode v = required(key);
    if (!v.isIntegralNumber() || !v.canConvertToInt()) {
      throw new IllegalArgumentException(at(key) + " is not an integer");
    }
    return v.intValue();
  }

  boolean bool(String key) {
    JsonNode v = required(key);
    if (!v.isBoolean()) {
      throw new IllegalArgumentException(at(key) + " is not a boolean");
    }
    return v.booleanValue();
  }

  Path path(String key) {
    return Paths.get(text(key));
  }

  Optional<Path> optionalPath(String key) {
    return optionalText(key).map(Paths::get);
  }

  Instant instant(String key) {
    String text = text(key);
    try {
      return Instant.parse(text);
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException(at(key) + " is not an ISO-8601 instant: " + text, e);
    }
  }

  Optional<Instant> optionalInstant(String key) {
    JsonNode v = node.get(key);
    return v == null || v.isNull() ? Optional.empty() : Optional.of(instant(key));
  }

  List<String> texts(String key) {
    List<String> out = new ArrayList<>();
    for (JsonNode v : array(key)) {
      if (!v.isTextual()) {
        throw new IllegalArgumentException(at(key) + " has a non-string element");
      }
      out.add(v.textValue());
    }
    return out;
  }

  List<Integer> ints(String key) {
    List<Integer> out = new ArrayList<>();
    for (JsonNode v : array(key)) {
      if (!v.isIntegralNumber() || !v.canConvertToInt()) {
        throw new IllegalArgumentException(at(key) + " has a non-integer element");
      }
      out.add(v.intValue());
    }
    return out;
  }

  List<Fields> objects(String key) {
    List<Fields> out = new ArrayList<>();
    JsonNode list = array(key);
    for (int i = 0; i < list.size(); i++) {
      out.add(new Fields(list.get(i), at(key) + "[" + i + "]"));
    }
    return out;
  }

  private JsonNode array(String key) {
    JsonNode v = required(key);
    if (!v.isArray()) {
      throw new IllegalArgumentException(at(key) + " is not an array");
    }
    return v;
  }
}
