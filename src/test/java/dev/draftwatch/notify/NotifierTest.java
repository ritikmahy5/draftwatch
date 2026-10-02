package dev.draftwatch.notify;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import dev.draftwatch.action.ActionFailedException;
import dev.draftwatch.action.NotifyAction;
import dev.draftwatch.detect.DetectorVerdict;
import dev.draftwatch.domain.Metric;
import dev.draftwatch.events.DetectionEvent;
import dev.draftwatch.events.DetectionSubject;
import dev.draftwatch.events.RegressionDetected;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class NotifierTest {
  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
  private static final DetectionSubject SUBJECT =
      DetectionSubject.of(
          "run", "chat", "a".repeat(64), 500, "sampled-x", "j1", Paths.get("/state/r.json"));

  private static RegressionDetected regression() {
    return (RegressionDetected)
        DetectionEvent.of(
            T0,
            SUBJECT,
            DetectorVerdict.builder(
                    DetectorVerdict.Kind.REGRESSION, "absolute_drop(x)", Metric.ALPHA)
                .explanation("dropped")
                .build());
  }

  private static final String LINE =
      "2026-01-01T00:00:00Z REGRESSION target run, step 500, probe chat, job j1:"
          + " absolute_drop(x): dropped; result /state/r.json";

  @Test
  public void alertLineNamesEverythingNeededToTraceIt() {
    assertEquals(LINE, AlertFormat.line(regression()));
  }

  @Test
  public void consoleNotifierPrintsAnAlertLine() {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    PrintStream stream = new PrintStream(bytes, true, StandardCharsets.UTF_8);
    new ConsoleNotifier(stream).notify(regression());
    assertEquals("ALERT " + LINE + "\n", bytes.toString(StandardCharsets.UTF_8));
  }

  @Test
  public void logFileNotifierAppendsOneLinePerAlert() throws IOException {
    Path log = tmp.getRoot().toPath().resolve("state").resolve(LogFileNotifier.FILE);
    LogFileNotifier notifier = new LogFileNotifier(log);
    notifier.notify(regression());
    notifier.notify(regression());
    assertEquals(LINE + "\n" + LINE + "\n", Files.readString(log));
  }

  @Test
  public void notifyActionTriesEveryNotifierAndReportsEachFailure() {
    List<String> delivered = new ArrayList<>();
    Notifier broken =
        new Notifier() {
          @Override
          public String name() {
            return "broken";
          }

          @Override
          public void notify(DetectionEvent event) throws IOException {
            throw new IOException("disk full");
          }
        };
    Notifier working =
        new Notifier() {
          @Override
          public String name() {
            return "working";
          }

          @Override
          public void notify(DetectionEvent event) {
            delivered.add(event.kind());
          }
        };
    try {
      new NotifyAction(List.of(broken, working)).execute(regression());
      fail("expected ActionFailedException");
    } catch (ActionFailedException e) {
      assertEquals("notify failed for broken: java.io.IOException: disk full", e.getMessage());
    }
    assertEquals(List.of("REGRESSION"), delivered);
    assertTrue(new NotifyAction(List.of()).name().equals("notify"));
  }
}
