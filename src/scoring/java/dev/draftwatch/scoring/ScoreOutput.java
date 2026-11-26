package dev.draftwatch.scoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;

/**
 * JSON for the score output. Objects are written with sorted keys and Jackson's default pretty
 * printer; doubles are written by Jackson's default writer, which round-trips, so no number is
 * rounded. An undefined value is {@code null} next to a reason, never NaN.
 */
final class ScoreOutput {
  static final JsonNodeFactory F = JsonNodeFactory.instance;

  private ScoreOutput() {}

  static ObjectNode rate(Rate r) {
    ObjectNode n = F.objectNode();
    put(n, "value", r.value());
    n.put("flagged", r.flagged());
    n.put("total", r.total());
    r.undefinedReason().ifPresent(reason -> n.put("undefined_reason", reason));
    return n;
  }

  static ObjectNode rate(Rate r, Optional<double[]> interval, String whyNoInterval) {
    ObjectNode n = rate(r);
    if (interval.isPresent()) {
      n.set("interval", F.arrayNode().add(interval.get()[0]).add(interval.get()[1]));
    } else {
      n.putNull("interval");
      n.put("interval_reason", whyNoInterval);
    }
    return n;
  }

  static void put(ObjectNode n, String key, OptionalDouble v) {
    if (v.isPresent()) {
      n.put(key, v.getAsDouble());
    } else {
      n.putNull(key);
    }
  }

  static void put(ObjectNode n, String key, OptionalLong v) {
    if (v.isPresent()) {
      n.put(key, v.getAsLong());
    } else {
      n.putNull(key);
    }
  }

  /** A copy of {@code node} with every object's keys sorted. */
  static JsonNode sorted(JsonNode node) {
    if (node.isObject()) {
      List<String> keys = new ArrayList<>();
      Iterator<String> it = node.fieldNames();
      while (it.hasNext()) {
        keys.add(it.next());
      }
      keys.sort(null);
      ObjectNode out = F.objectNode();
      for (String k : keys) {
        out.set(k, sorted(node.get(k)));
      }
      return out;
    }
    if (node.isArray()) {
      ArrayNode out = F.arrayNode();
      for (JsonNode e : node) {
        out.add(sorted(e));
      }
      return out;
    }
    return node;
  }

  static byte[] bytes(ObjectMapper json, JsonNode node) {
    try {
      String text = json.writerWithDefaultPrettyPrinter().writeValueAsString(sorted(node));
      return (text + "\n").getBytes(StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("cannot serialize the score output", e);
    }
  }

  static ObjectNode object(Map<String, ? extends JsonNode> fields) {
    ObjectNode n = F.objectNode();
    for (Map.Entry<String, ? extends JsonNode> e : fields.entrySet()) {
      n.set(e.getKey(), e.getValue());
    }
    return n;
  }
}
