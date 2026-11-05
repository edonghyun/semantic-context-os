package io.scos.server;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.scos.collection.internal.AccessResult;
import io.scos.collection.internal.FixedSourceQueries;
import io.scos.collection.internal.SourceInstances;
import io.scos.collection.internal.SourceQueries;
import io.scos.collection.internal.SourceQueries.AccessStatus;
import io.scos.engine.support.time.MutableClock;
import io.scos.spi.source.SourceInstanceId;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

final class AccessGaugeScenario {
  private static final Instant NOW = Instant.parse("2026-10-14T00:00:00Z");
  private static final SourceInstanceId SOURCE = new SourceInstanceId("drive-a");
  final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final MutableClock clock = new MutableClock(NOW);
  private AccessStatus status = status(3);

  private AccessStatus status(int stale) {
    return new AccessStatus(
        Optional.of(AccessResult.INCOMPLETE),
        Optional.of(NOW),
        0,
        1,
        1,
        0,
        stale,
        Optional.of(NOW),
        false,
        2,
        Optional.of(NOW.minusSeconds(60)),
        4,
        Set.of("file:F1"));
  }

  void changeStaleCount(int stale) {
    status = status(stale);
  }

  void bind(boolean enabled, boolean observed) {
    var source = SourceInstances.of(SOURCE, "google-drive", "{}", "env:FAKE", NOW);
    var queries =
        new FixedSourceQueries(List.of(source), observed ? Map.of(SOURCE, status) : Map.of());
    new AccessGauges(changingStatus(queries), clock, enabled).bindTo(registry);
  }

  private SourceQueries changingStatus(FixedSourceQueries queries) {
    return (SourceQueries)
        Proxy.newProxyInstance(
            SourceQueries.class.getClassLoader(),
            new Class<?>[] {SourceQueries.class},
            (proxy, method, args) -> {
              if (method.getName().equals("accessStatus"))
                return queries.accessStatus((SourceInstanceId) args[0]).map(ignored -> status);
              try {
                return method.invoke(queries, args);
              } catch (InvocationTargetException failure) {
                throw failure.getCause();
              }
            });
  }

  double value(String name) {
    return registry.get(name).tag("source", "drive-a").gauge().value();
  }

  Set<String> tagKeys() {
    return registry.getMeters().stream()
        .flatMap(meter -> meter.getId().getTags().stream())
        .map(tag -> tag.getKey())
        .collect(Collectors.toSet());
  }

  void nextScrape() {
    clock.advance(Duration.ofSeconds(1));
  }
}
