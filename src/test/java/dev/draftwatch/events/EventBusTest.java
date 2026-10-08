package dev.draftwatch.events;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import dev.draftwatch.detect.DetectorVerdict;
import dev.draftwatch.domain.Metric;
import java.io.IOException;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class EventBusTest {
  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

  private final List<String> diagnostics = new ArrayList<>();
  private final EventBus bus = new EventBus(diagnostics::add, Clock.fixed(T0, ZoneOffset.UTC));

  private static final DetectionSubject SUBJECT =
      DetectionSubject.of("run", "chat", "a".repeat(64), 100, "sampled-x", "j1", Paths.get("r"));

  private static DetectionEvent event(DetectorVerdict.Kind kind) {
    return DetectionEvent.of(
        T0,
        SUBJECT,
        DetectorVerdict.builder(kind, "absolute_drop(…)", Metric.ALPHA).explanation("e").build());
  }

  @Test
  public void deliversByTypeAndSupertypeInSubscriptionOrder() {
    List<String> seen = new ArrayList<>();
    bus.subscribe(DetectionEvent.class, "all", e -> seen.add("all:" + e.kind()));
    bus.subscribe(RegressionDetected.class, "regressions", e -> seen.add("regression"));
    bus.subscribe(Event.class, "everything", e -> seen.add("event"));
    bus.publish(event(DetectorVerdict.Kind.REGRESSION));
    bus.publish(event(DetectorVerdict.Kind.OK));
    assertEquals(List.of("all:REGRESSION", "regression", "event", "all:OK", "event"), seen);
  }

  // --- a throwing subscriber does not stop delivery to the others ---

  @Test
  public void throwingSubscriberDoesNotStopDeliveryToTheOthers() {
    List<String> seen = new ArrayList<>();
    List<SubscriberFailed> failures = new ArrayList<>();
    bus.subscribe(SubscriberFailed.class, "failures", failures::add);
    bus.subscribe(DetectionError.class, "first", e -> seen.add("first"));
    bus.subscribe(
        DetectionError.class,
        "broken",
        e -> {
          throw new IllegalStateException("disk full");
        });
    bus.subscribe(
        DetectionError.class,
        "checked",
        e -> {
          throw new IOException("no such file");
        });
    bus.subscribe(DetectionError.class, "last", e -> seen.add("last"));

    bus.publish(event(DetectorVerdict.Kind.ERROR));

    assertEquals(List.of("first", "last"), seen);
    assertEquals(2, failures.size());
    assertEquals("broken", failures.get(0).subscriber());
    assertEquals("java.lang.IllegalStateException: disk full", failures.get(0).error());
    assertEquals("checked", failures.get(1).subscriber());
    assertEquals(T0, failures.get(0).at());
    assertEquals(2, diagnostics.size());
    assertTrue(diagnostics.get(0), diagnostics.get(0).startsWith("draftwatch: subscriber broken"));
  }

  @Test
  public void failureWhileReportingAFailureDoesNotRecurse() {
    bus.subscribe(
        SubscriberFailed.class,
        "bad reporter",
        e -> {
          throw new IllegalStateException("also broken");
        });
    bus.subscribe(
        DetectionOk.class,
        "broken",
        e -> {
          throw new IllegalStateException("broken");
        });
    bus.publish(event(DetectorVerdict.Kind.OK));
    assertEquals(2, diagnostics.size());
    assertTrue(diagnostics.get(1), diagnostics.get(1).contains("subscriber bad reporter failed"));
  }

  @Test
  public void subscribersMayPublishWhileHandling() {
    List<String> seen = new ArrayList<>();
    bus.subscribe(DetectionOk.class, "relay", e -> bus.publish(event(DetectorVerdict.Kind.ERROR)));
    bus.subscribe(DetectionError.class, "sink", e -> seen.add(e.kind()));
    bus.publish(event(DetectorVerdict.Kind.OK));
    assertEquals(List.of("ERROR"), seen);
  }

  @Test
  public void verdictKindsMapToTheirEventClasses() {
    assertEquals(DetectionOk.class, event(DetectorVerdict.Kind.OK).getClass());
    assertEquals(RegressionDetected.class, event(DetectorVerdict.Kind.REGRESSION).getClass());
    assertEquals(DetectionError.class, event(DetectorVerdict.Kind.ERROR).getClass());
    assertEquals(
        DetectionInsufficientData.class,
        event(DetectorVerdict.Kind.INSUFFICIENT_DATA).getClass());
    assertEquals(
        "DEFERRED", new DetectionDeferred(T0, SUBJECT, "baseline not measured").kind());
  }

  @Test(expected = IllegalArgumentException.class)
  public void eventClassRejectsAVerdictOfAnotherKind() {
    new RegressionDetected(
        T0,
        SUBJECT,
        DetectorVerdict.builder(DetectorVerdict.Kind.OK, "d", Metric.ALPHA)
            .explanation("e")
            .build());
  }
}
