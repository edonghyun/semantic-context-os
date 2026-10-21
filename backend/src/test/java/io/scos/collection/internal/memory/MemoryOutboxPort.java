package io.scos.collection.internal.memory;

import io.scos.collection.internal.OutboxPort;
import io.scos.collection.internal.ProjectionRetryPolicy;
import io.scos.spi.connector.SourceInstanceId;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

public class MemoryOutboxPort implements OutboxPort {
  private final Map<SourceInstanceId, Request> requests = new HashMap<>();

  @Override
  public synchronized void requestProjection(SourceInstanceId source, Instant now) {
    Request prev = requests.get(source);
    requests.put(
        source,
        new Request(
            source,
            prev == null ? 1 : prev.version() + 1,
            now,
            0,
            prev == null ? 0 : prev.consecutiveFailures(),
            Optional.empty(),
            false,
            prev == null ? Optional.empty() : prev.lastError()));
  }

  private List<SourceInstanceId> ordered(Predicate<Request> filter) {
    return requests.values().stream()
        .filter(filter)
        .sorted(Comparator.comparing(Request::requestedAt).thenComparing(r -> r.source().value()))
        .map(Request::source)
        .toList();
  }

  @Override
  public synchronized List<SourceInstanceId> pending() {
    return ordered(r -> true);
  }

  @Override
  public synchronized List<SourceInstanceId> due(Instant now) {
    return ordered(r -> !r.parked() && r.nextAttemptAt().map(t -> !t.isAfter(now)).orElse(true));
  }

  @Override
  public synchronized Summary summary() {
    int parked = (int) requests.values().stream().filter(Request::parked).count();
    int maxFailures =
        requests.values().stream().mapToInt(Request::consecutiveFailures).max().orElse(0);
    return new Summary(requests.size() - parked, parked, maxFailures);
  }

  @Override
  public synchronized Optional<Request> request(SourceInstanceId source) {
    return Optional.ofNullable(requests.get(source));
  }

  @Override
  public synchronized boolean complete(SourceInstanceId source, long seenVersion) {
    Request prev = requests.get(source);
    if (prev == null || prev.version() != seenVersion) return false;
    requests.remove(source);
    return true;
  }

  @Override
  public synchronized boolean recordFailure(
      SourceInstanceId source,
      long seenVersion,
      Instant now,
      String error,
      ProjectionRetryPolicy policy) {
    Request prev = requests.get(source);
    if (prev == null || prev.version() != seenVersion) return false;
    int attempts = prev.attempts() + 1;
    requests.put(
        source,
        new Request(
            source,
            prev.version(),
            prev.requestedAt(),
            attempts,
            prev.consecutiveFailures() + 1,
            Optional.of(now.plus(policy.backoff(attempts))),
            policy.parks(attempts),
            Optional.of(error)));
    return true;
  }
}
