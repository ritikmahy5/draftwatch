package dev.draftwatch.config;

import com.fasterxml.jackson.databind.JsonNode;
import dev.draftwatch.domain.Names;
import dev.draftwatch.domain.WireNamed;
import java.math.BigDecimal;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A position in the config tree that knows its field path and records problems instead of
 * throwing, so one validation pass reports every error.
 *
 * <p>Conventions: {@link #required} and {@link #optional} report a missing or valueless key.
 * Every {@code asX} method returns {@code null} when the node has no value (already reported,
 * or an absent optional key the caller handles) or when it reports a type or range error.
 */
final class ConfigNode {
  static final String ROOT = "<root>";

  private final JsonNode node;
  private final String path;
  private final List<ConfigError> errors;

  private ConfigNode(JsonNode node, String path, List<ConfigError> errors) {
    this.node = node;
    this.path = path;
    this.errors = errors;
  }

  static ConfigNode root(JsonNode node, List<ConfigError> errors) {
    return new ConfigNode(node, "", errors);
  }

  String path() {
    return path.isEmpty() ? ROOT : path;
  }

  /** The key is not in the file at all. */
  boolean isAbsent() {
    return node == null || node.isMissingNode();
  }

  /** The key is present with a non-null value. */
  boolean hasValue() {
    return !isAbsent() && !node.isNull();
  }

  boolean isNumber() {
    return hasValue() && node.isNumber();
  }

  boolean isText() {
    return hasValue() && node.isTextual();
  }

  boolean isList() {
    return hasValue() && node.isArray();
  }

  /**
   * The text of child {@code key} if it is a valid identifier, else null; reports nothing. Used
   * to detect duplicate ids across list entries before each entry is validated.
   */
  String peekName(String key) {
    JsonNode value = node != null && node.isObject() ? node.get(key) : null;
    return value != null && value.isTextual() && Names.isValid(value.textValue())
        ? value.textValue()
        : null;
  }

  void error(String message) {
    errors.add(ConfigError.of(path(), message));
  }

  private String childPath(String key) {
    return path.isEmpty() ? key : path + "." + key;
  }

  private ConfigNode child(String key) {
    JsonNode value = node != null && node.isObject() ? node.get(key) : null;
    return new ConfigNode(value, childPath(key), errors);
  }

  /** The child {@code key}, reporting it if missing or valueless. */
  ConfigNode required(String key) {
    return required(key, "required");
  }

  /** Like {@link #required(String)} with a specific message for a missing key. */
  ConfigNode required(String key, String missingMessage) {
    ConfigNode child = child(key);
    if (child.isAbsent()) {
      child.error(missingMessage);
    } else if (child.node.isNull()) {
      child.error("has no value");
    }
    return child;
  }

  /** The child {@code key}, which may be absent; a present key without a value is reported. */
  ConfigNode optional(String key) {
    ConfigNode child = child(key);
    if (!child.isAbsent() && child.node.isNull()) {
      child.error("has no value; give one or remove the key");
    }
    return child;
  }

  /**
   * True if this is a mapping; reports a non-mapping and every key not in {@code allowedKeys}.
   * Returns false without reporting if there is no value.
   */
  boolean mapping(String... allowedKeys) {
    if (!hasValue()) {
      return false;
    }
    if (!node.isObject()) {
      error("must be a mapping");
      return false;
    }
    Set<String> allowed = new LinkedHashSet<>(Arrays.asList(allowedKeys));
    for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
      String key = it.next();
      if (!allowed.contains(key)) {
        child(key).error("unknown key (allowed: " + String.join(", ", allowed) + ")");
      }
    }
    return true;
  }

  /**
   * The elements of a list. Reports a non-list and valueless elements; returns an empty list
   * if there is no value.
   */
  List<ConfigNode> elements() {
    List<ConfigNode> out = new ArrayList<>();
    if (!hasValue()) {
      return out;
    }
    if (!node.isArray()) {
      error("must be a list");
      return out;
    }
    for (int i = 0; i < node.size(); i++) {
      ConfigNode element = new ConfigNode(node.get(i), path + "[" + i + "]", errors);
      if (!element.hasValue()) {
        element.error("has no value");
      }
      out.add(element);
    }
    return out;
  }

  /** Like {@link #elements()}, also reporting an empty list with {@code emptyMessage}. */
  List<ConfigNode> elements(String emptyMessage) {
    List<ConfigNode> out = elements();
    if (isList() && out.isEmpty()) {
      error(emptyMessage);
    }
    return out;
  }

  /**
   * For list items written as a one-key mapping ({@code - max_pending: 4}): the key and its
   * value node. Returns null after reporting anything else.
   */
  Map.Entry<String, ConfigNode> singleKey(String what) {
    if (!hasValue()) {
      return null;
    }
    if (!node.isObject() || node.size() != 1) {
      error("must be a mapping with exactly one key naming the " + what);
      return null;
    }
    String key = node.fieldNames().next();
    ConfigNode value = child(key);
    if (!value.hasValue()) {
      value.error("has no value; write '" + key + ": {}' if it takes no parameters");
      return null;
    }
    return new AbstractMap.SimpleImmutableEntry<>(key, value);
  }

  /** True if this is a mapping with no keys ({@code {}}); reports anything else. */
  boolean emptyMapping(String message) {
    if (!hasValue()) {
      return false;
    }
    if (!node.isObject() || node.size() != 0) {
      error(message);
      return false;
    }
    return true;
  }

  String asString() {
    if (!hasValue()) {
      return null;
    }
    if (!node.isTextual()) {
      error("must be a string");
      return null;
    }
    String text = node.textValue();
    if (text.trim().isEmpty()) {
      error("must not be blank");
      return null;
    }
    return text;
  }

  /** An identifier per {@link Names}. */
  String asName() {
    String text = asString();
    if (text == null) {
      return null;
    }
    if (!Names.isValid(text)) {
      error("'" + text + "' " + Names.RULE);
      return null;
    }
    return text;
  }

  /** A single file name, not a path. */
  String asFileName() {
    String text = asString();
    if (text == null) {
      return null;
    }
    if (text.contains("/") || text.contains("\\") || text.equals(".") || text.equals("..")) {
      error("must be a file name, not a path, was '" + text + "'");
      return null;
    }
    return text;
  }

  /** A path, resolved against {@code baseDir} if relative, and normalized. */
  Path asPath(Path baseDir) {
    String text = asString();
    if (text == null) {
      return null;
    }
    if (text.startsWith("~")) {
      error("'~' is not expanded; use an absolute path or one relative to the config file");
      return null;
    }
    try {
      return baseDir.resolve(text).normalize();
    } catch (InvalidPathException e) {
      error("is not a valid path: " + e.getMessage());
      return null;
    }
  }

  /** An integer that fits in an {@code int} and is at least {@code min}. */
  Integer asInt(int min) {
    Long value = asLong(min);
    if (value == null) {
      return null;
    }
    if (value > Integer.MAX_VALUE) {
      error("must be at most " + Integer.MAX_VALUE + ", was " + value);
      return null;
    }
    return value.intValue();
  }

  /** An integer that fits in a {@code long} and is at least {@code min}. */
  Long asLong(long min) {
    if (!hasValue()) {
      return null;
    }
    if (!node.isIntegralNumber() || !node.canConvertToLong()) {
      error("must be an integer");
      return null;
    }
    long value = node.longValue();
    if (value < min) {
      error("must be >= " + min + ", was " + value);
      return null;
    }
    return value;
  }

  /** A finite number, exactly as written. */
  BigDecimal asDecimal() {
    if (!hasValue()) {
      return null;
    }
    if (!node.isNumber()) {
      error("must be a number");
      return null;
    }
    if ((node.isDouble() || node.isFloat())
        && (Double.isNaN(node.doubleValue()) || Double.isInfinite(node.doubleValue()))) {
      error("must be a finite number");
      return null;
    }
    return node.decimalValue();
  }

  /** A finite number as a double. */
  Double asDouble() {
    BigDecimal value = asDecimal();
    if (value == null) {
      return null;
    }
    double d = value.doubleValue();
    if (Double.isInfinite(d)) {
      error("must be a finite number");
      return null;
    }
    return d;
  }

  Boolean asBoolean() {
    if (!hasValue()) {
      return null;
    }
    if (!node.isBoolean()) {
      error("must be true or false");
      return null;
    }
    return node.booleanValue();
  }

  /** One of {@code type}'s wire names. */
  <E extends Enum<E> & WireNamed> E asEnum(Class<E> type) {
    return asEnum(type, "");
  }

  /** One of {@code type}'s wire names; {@code hint} is appended to the error message. */
  <E extends Enum<E> & WireNamed> E asEnum(Class<E> type, String hint) {
    String text = asString();
    if (text == null) {
      return null;
    }
    E value = WireNamed.parse(type, text).orElse(null);
    if (value == null) {
      error(
          "must be one of "
              + WireNamed.allNames(type)
              + ", was '"
              + text
              + "'"
              + (hint.isEmpty() ? "" : "; " + hint));
    }
    return value;
  }
}
