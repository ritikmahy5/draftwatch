package dev.draftwatch.config;

import com.fasterxml.jackson.core.io.JsonStringEncoder;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Serializes a JSON tree to one canonical text, so that equal content always hashes equally
 * (ARCHITECTURE.md, "Canonical hashing").
 *
 * <ul>
 *   <li>Object keys are sorted by {@link String#compareTo} (UTF-16 code units).
 *   <li>No whitespace is emitted.
 *   <li>Numbers are written by value: {@code 0}, {@code 0.0}, {@code -0.0} and {@code 0e0}
 *       all become {@code 0}; {@code 1.50} and {@code 15e-1} become {@code 1.5}; integral
 *       values have no decimal point; exponents are expanded ({@code 1e2} becomes {@code 100}).
 *       NaN and infinities are rejected.
 *   <li>Arrays keep their order. Strings use JSON escapes for quotes, backslashes, and control
 *       characters; other characters are written as UTF-8.
 * </ul>
 */
public final class CanonicalJson {
  private CanonicalJson() {}

  /**
   * The canonical text of {@code node}.
   *
   * @throws IllegalArgumentException if the tree holds NaN, an infinity, or a non-JSON node
   */
  public static String write(JsonNode node) {
    StringBuilder out = new StringBuilder();
    append(node, out);
    return out.toString();
  }

  /** The UTF-8 bytes of {@link #write}. */
  public static byte[] bytes(JsonNode node) {
    return write(node).getBytes(StandardCharsets.UTF_8);
  }

  private static void append(JsonNode node, StringBuilder out) {
    switch (node.getNodeType()) {
      case OBJECT:
        appendObject(node, out);
        break;
      case ARRAY:
        out.append('[');
        for (int i = 0; i < node.size(); i++) {
          if (i > 0) {
            out.append(',');
          }
          append(node.get(i), out);
        }
        out.append(']');
        break;
      case STRING:
        appendString(node.textValue(), out);
        break;
      case NUMBER:
        out.append(number(node));
        break;
      case BOOLEAN:
        out.append(node.booleanValue() ? "true" : "false");
        break;
      case NULL:
        out.append("null");
        break;
      default:
        throw new IllegalArgumentException("not a JSON value: " + node.getNodeType());
    }
  }

  private static void appendObject(JsonNode node, StringBuilder out) {
    List<String> keys = new ArrayList<>();
    for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
      keys.add(it.next());
    }
    Collections.sort(keys);
    out.append('{');
    for (int i = 0; i < keys.size(); i++) {
      if (i > 0) {
        out.append(',');
      }
      appendString(keys.get(i), out);
      out.append(':');
      append(node.get(keys.get(i)), out);
    }
    out.append('}');
  }

  private static void appendString(String text, StringBuilder out) {
    out.append('"');
    out.append(JsonStringEncoder.getInstance().quoteAsString(text));
    out.append('"');
  }

  /** The canonical spelling of a numeric node's value. */
  static String number(JsonNode node) {
    if (node.isIntegralNumber()) {
      return node.bigIntegerValue().toString();
    }
    if (node.isDouble() || node.isFloat()) {
      double value = node.doubleValue();
      if (Double.isNaN(value) || Double.isInfinite(value)) {
        throw new IllegalArgumentException("NaN and infinity are not valid JSON numbers");
      }
    }
    return canonicalDecimal(node.decimalValue());
  }

  /** {@code value} with trailing zeros stripped and no exponent; zero is {@code 0}. */
  public static String canonicalDecimal(BigDecimal value) {
    if (value.signum() == 0) {
      return "0";
    }
    return value.stripTrailingZeros().toPlainString();
  }
}
