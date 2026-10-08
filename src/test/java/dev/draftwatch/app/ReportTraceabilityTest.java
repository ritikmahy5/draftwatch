package dev.draftwatch.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * The generated HTML is parsed, every displayed number is matched to the
 * value in the stored result file it links to, and no number lacks a link. The
 * results come from the fake harness and synthetic fixtures: a baseline, a later OK checkpoint, a
 * regression, a two-seed series, and an incomparable (ERROR) series.
 */
public class ReportTraceabilityTest {
  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private final CommandTestSupport cli = new CommandTestSupport();
  private final ObjectMapper json = new ObjectMapper();
  private final Map<Path, JsonNode> files = new HashMap<>();
  private Project p;
  private Path report;
  private Document page;

  private void submit(int step, byte weight) {
    int exit =
        cli.run(
            "submit", "run", p.checkpoint(step, weight).toString(), "--config",
            p.config().toString());
    assertTrue(cli.out() + cli.err(), exit == Cli.EXIT_OK || exit == Cli.EXIT_FAILURE);
  }

  @Before
  public void setUp() throws Exception {
    p = new Project(tmp.getRoot().toPath().toAbsolutePath()).write();
    submit(100, (byte) 1); // the baseline, pinned automatically
    submit(200, (byte) 2);
    p.fixture("synthetic_three_prompts_lower.json").write();
    submit(300, (byte) 3); // a regression against the baseline
    p.fixture("synthetic_two_seeds.json").sampling("0.7", "[0, 1]").write();
    submit(400, (byte) 4); // another probe hash: its own series, with standard deviations
    p.fixture("synthetic_three_prompts.json").sampling("0", "[0]")
        .fakeEnv("DRAFTWATCH_FAKE_HARNESS_VERSION", "0.2.0").write();
    submit(500, (byte) 5); // another harness version: incomparable, an ERROR

    Path outDir = Files.createDirectories(tmp.getRoot().toPath().resolve("it's out #1"));
    report = outDir.resolve("report.html");
    assertEquals(
        cli.err(),
        Cli.EXIT_OK,
        cli.run("report", "--out", report.toString(), "--config", p.config().toString()));
    page = parse(report);
  }

  private static Document parse(Path file) throws Exception {
    DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
    f.setNamespaceAware(true);
    f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
    f.setFeature("http://xml.org/sax/features/external-general-entities", false);
    f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    f.setExpandEntityReferences(false);
    DocumentBuilder b = f.newDocumentBuilder();
    return b.parse(file.toFile());
  }

  /** The file an href points to, relative to the report, and its decoded fragment. */
  private String[] resolve(String href) {
    int hash = href.indexOf('#');
    String path = hash < 0 ? href : href.substring(0, hash);
    String fragment = hash < 0 ? null : URI.create(href.substring(hash)).getFragment();
    Path file = report.getParent().resolve(URI.create(path).getPath()).normalize();
    return new String[] {file.toString(), fragment};
  }

  private JsonNode read(Path file) {
    return files.computeIfAbsent(
        file,
        f -> {
          try {
            return json.readTree(f.toFile());
          } catch (IOException e) {
            throw new AssertionError("cannot read linked file " + f, e);
          }
        });
  }

  private static Element linkAncestor(Node n) {
    for (Node a = n.getParentNode(); a != null; a = a.getParentNode()) {
      if (a instanceof Element
          && ((Element) a).getLocalName().equals("a")
          && ((Element) a).hasAttribute("href")) {
        return (Element) a;
      }
    }
    return null;
  }

  private static void textNodes(Node n, List<Node> out) {
    if (n instanceof Element && ((Element) n).getLocalName().equals("style")) {
      return;
    }
    if (n.getNodeType() == Node.TEXT_NODE) {
      out.add(n);
    }
    NodeList children = n.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      textNodes(children.item(i), out);
    }
  }

  /** Why {@code shown} does not match the value its link points at, or null if it does. */
  private String mismatch(String shown, Element link) {
    String[] target = resolve(link.getAttribute("href"));
    Path file = Path.of(target[0]);
    if (!Files.isRegularFile(file)) {
      return "'" + shown + "' links to a missing file " + file;
    }
    if (target[1] == null) {
      return "'" + shown + "' links to " + file + " without a JSON Pointer";
    }
    JsonNode value = read(file).at(target[1]);
    if (value.isNumber()) {
      return Double.parseDouble(shown) == value.doubleValue()
          ? null
          : "'" + shown + "' but " + file.getFileName() + "#" + target[1] + " is " + value;
    }
    if (value.isTextual()) {
      return shown.equals(value.textValue())
          ? null
          : "'" + shown + "' but " + file.getFileName() + "#" + target[1] + " is " + value;
    }
    return "'" + shown + "' links to " + target[1] + ", which is " + value;
  }

  @Test
  public void everyDisplayedNumberLinksToTheValueInItsStoredResultFile() {
    assertEquals(List.of(), untraced(page));
    assertTrue("the page shows numbers: " + numbers, numbers > 50);
  }

  @Test
  public void theCheckCatchesAChangedValueAndAnUnlinkedNumber() throws Exception {
    String html = Files.readString(report, StandardCharsets.UTF_8);
    String marker = "#/report/aggregate/alpha_mean\">";
    int at = html.indexOf(marker) + marker.length();
    String tampered =
        html.substring(0, at) + "1" + html.substring(at) // 0.46... becomes 10.46...
            .replaceFirst("</main>", "<p>42</p></main>");
    Path copy = report.resolveSibling("tampered.html");
    Files.writeString(copy, tampered, StandardCharsets.UTF_8);
    List<String> failures = untraced(parse(copy));
    assertEquals(failures.toString(), 2, failures.size());
    assertTrue(failures.get(0), failures.get(0).contains("but "));
    assertTrue(failures.get(1), failures.get(1).equals("'42' has no link"));
  }

  private int numbers;

  /** Every text with a digit that is not a link to an equal stored value. */
  private List<String> untraced(Document doc) {
    List<Node> texts = new ArrayList<>();
    textNodes(doc.getDocumentElement(), texts);
    List<String> failures = new ArrayList<>();
    numbers = 0;
    for (Node t : texts) {
      String shown = t.getNodeValue().trim();
      if (!shown.chars().anyMatch(Character::isDigit)) {
        continue;
      }
      numbers++;
      Element link = linkAncestor(t);
      if (link == null) {
        failures.add("'" + shown + "' has no link");
        continue;
      }
      String why = mismatch(shown, link);
      if (why != null) {
        failures.add(why);
      }
    }
    return failures;
  }

  @Test
  public void everyLinkPointsAtAStoredFileAndEveryResultIsShown() throws IOException {
    NodeList links = page.getElementsByTagNameNS("*", "a");
    List<Path> resultFiles;
    try (Stream<Path> walk = Files.walk(p.state().resolve("results"))) {
      resultFiles = walk.filter(Files::isRegularFile).collect(Collectors.toList());
    }
    assertEquals(5, resultFiles.size());
    List<String> linked = new ArrayList<>();
    for (int i = 0; i < links.getLength(); i++) {
      String file = resolve(((Element) links.item(i)).getAttribute("href"))[0];
      assertTrue(file, Files.isRegularFile(Path.of(file)));
      linked.add(file);
    }
    for (Path f : resultFiles) {
      assertTrue(f + " is linked", linked.contains(f.toAbsolutePath().normalize().toString()));
    }
    assertTrue(linked.stream().anyMatch(f -> f.endsWith(File.separator + "report.json")));
    assertTrue(linked.stream().anyMatch(f -> f.endsWith(File.separator + "detections.log")));
  }

  @Test
  public void markersLabelsAndNoExternalAssets() throws IOException {
    String html = Files.readString(report, StandardCharsets.UTF_8);
    assertTrue("a regression is marked", html.contains("class=\"mk-regression\""));
    assertTrue("an error is marked", html.contains("class=\"mk-error\""));
    assertTrue(html.contains("paired_bootstrap on alpha: REGRESSION"));
    assertTrue("inexact counts are labeled", html.contains(">approximate</span>"));
    assertTrue("two seeds give standard deviations", html.contains(">alpha_std</th>"));
    assertFalse("alpha is never called a probability", html.contains("probability"));
    String withoutNamespaces =
        html.replace("http://www.w3.org/1999/xhtml", "").replace("http://www.w3.org/2000/svg", "");
    assertFalse("no external assets", withoutNamespaces.contains("http"));
    assertFalse(html.contains("<script"));
    assertFalse(html.contains("<link"));
    assertEquals(
        "probe chat has three series: two probe hashes, and a second harness version",
        3,
        page.getElementsByTagNameNS("*", "h3").getLength());
  }
}
