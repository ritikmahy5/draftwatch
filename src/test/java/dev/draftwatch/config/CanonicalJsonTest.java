package dev.draftwatch.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class CanonicalJsonTest {
  private static final ObjectMapper DOUBLES = new ObjectMapper();
  private static final ObjectMapper DECIMALS =
      new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

  private static String canonical(ObjectMapper mapper, String json) throws IOException {
    return CanonicalJson.write(mapper.readTree(json));
  }

  /** Asserts the same canonical text whether floats are parsed as doubles or as decimals. */
  private static void assertCanonical(String expected, String json) throws IOException {
    assertEquals("doubles: " + json, expected, canonical(DOUBLES, json));
    assertEquals("decimals: " + json, expected, canonical(DECIMALS, json));
  }

  @Test
  public void sortsKeysRecursivelyAndKeepsArrayOrder() throws IOException {
    assertCanonical(
        "{\"a\":{\"c\":null,\"d\":[3,1,2]},\"b\":1}",
        "{\"b\": 1, \"a\": {\"d\": [3, 1, 2], \"c\": null}}");
  }

  @Test
  public void keyOrderDoesNotChangeOutput() throws IOException {
    assertEquals(
        canonical(DOUBLES, "{\"x\":1,\"y\":{\"p\":true,\"q\":false}}"),
        canonical(DOUBLES, "{\"y\":{\"q\":false,\"p\":true},\"x\":1}"));
  }

  @Test
  public void removesAllWhitespace() throws IOException {
    assertCanonical("{\"a\":[1,2],\"b\":\"x y\"}", "{\n  \"a\" : [ 1 ,\t2 ],\r\n \"b\":\"x y\" }");
  }

  @Test
  public void allSpellingsOfZeroAreIdentical() throws IOException {
    for (String zero : new String[] {"0", "0.0", "0e0", "-0.0", "0.000", "0E+5", "-0"}) {
      assertCanonical("{\"t\":0}", "{\"t\":" + zero + "}");
    }
  }

  @Test
  public void numbersAreWrittenByValue() throws IOException {
    assertCanonical(
        "[1.5,1.5,100,100,0.1,-2.5,0.0000001,7]",
        "[1.50, 15e-1, 1e2, 100.0, 0.1, -2.50, 1e-7, 7.000]");
  }

  @Test
  public void largeIntegersAreExact() throws IOException {
    assertCanonical("[12345678901234567890,-9]", "[12345678901234567890, -9]");
  }

  @Test
  public void stringsAreEscapedDeterministically() throws IOException {
    assertCanonical(
        "{\"k\\\"ey\":\"a\\\"b\\\\c\\n\\u0001é/\"}",
        "{\"k\\\"ey\": \"a\\\"b\\\\c\\n\\u0001\\u00e9/\"}");
  }

  @Test
  public void literalsAreWrittenAsIs() throws IOException {
    assertCanonical("[true,false,null]", "[true, false, null]");
  }

  @Test
  public void rejectsNaNAndInfinity() {
    for (double bad : new double[] {Double.NaN, Double.POSITIVE_INFINITY}) {
      ObjectNode node = JsonNodeFactory.instance.objectNode();
      node.set("x", DoubleNode.valueOf(bad));
      try {
        CanonicalJson.write(node);
        fail("expected rejection of " + bad);
      } catch (IllegalArgumentException expected) {
        // expected
      }
    }
  }

  @Test
  public void bytesAreUtf8OfText() throws IOException {
    JsonNode node = DOUBLES.readTree("{\"s\":\"é\"}");
    byte[] bytes = CanonicalJson.bytes(node);
    assertEquals("{\"s\":\"é\"}", new String(bytes, StandardCharsets.UTF_8));
    assertEquals("9 characters, one of them 2 bytes in UTF-8", 10, bytes.length);
  }
}
