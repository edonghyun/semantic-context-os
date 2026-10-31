package io.scos.collection.internal.memory;

import io.scos.collection.internal.BodyCompletionStore;
import io.scos.collection.internal.ReconciliationDiscovery.Comparison;
import io.scos.collection.internal.ReconciliationDiscovery.Discovery;
import io.scos.collection.internal.RunId;
import io.scos.spi.connector.SourceInstanceId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** 공유 계약으로 검증하는 본문 완료 메모리 저장소. 트랜잭션은 모델링하지 않는다. */
public final class MemoryBodyCompletionStore implements BodyCompletionStore {
  private record Key(SourceInstanceId source, String fingerprint, String pageId) {}

  private record DiscoveryKey(
      SourceInstanceId source, RunId run, String resourceId, String stateKey) {}

  private record ComparisonKey(
      SourceInstanceId source, RunId run, String fingerprint, String pageId) {}

  private final Map<ComparisonKey, Comparison> comparisons = new HashMap<>();
  private final Map<DiscoveryKey, Discovery> discoveries = new HashMap<>();

  private final Map<Key, Body> bodies = new HashMap<>();
  private final Map<Key, Set<String>> members = new HashMap<>();

  private final Map<Key, Pending> pending = new HashMap<>();

  @Override
  public synchronized void savePending(SourceInstanceId source, Pending value) {
    Key key = new Key(source, value.fingerprint(), value.pageId());
    pending.put(key, value);
    var owned = new HashSet<>(members.getOrDefault(key, Set.of()));
    owned.addAll(value.observed());
    members.put(key, Set.copyOf(owned));
  }

  @Override
  public synchronized Optional<Pending> pending(
      SourceInstanceId source, String fingerprint, String pageId) {
    return Optional.ofNullable(pending.get(new Key(source, fingerprint, pageId)));
  }

  @Override
  public synchronized boolean hasPending(SourceInstanceId source, String fingerprint, RunId run) {
    return pending.entrySet().stream()
        .anyMatch(
            entry ->
                entry.getKey().source().equals(source)
                    && entry.getKey().fingerprint().equals(fingerprint)
                    && entry.getValue().run().equals(run));
  }

  @Override
  public synchronized void save(SourceInstanceId source, Body body) {
    bodies.put(new Key(source, body.fingerprint(), body.pageId()), body);
    members.put(new Key(source, body.fingerprint(), body.pageId()), body.blocks());
    pending.remove(new Key(source, body.fingerprint(), body.pageId()));
  }

  @Override
  public synchronized Optional<Body> find(
      SourceInstanceId source, String fingerprint, String pageId) {
    return Optional.ofNullable(bodies.get(new Key(source, fingerprint, pageId)));
  }

  @Override
  public synchronized Set<String> owners(
      SourceInstanceId source, String fingerprint, String blockId) {
    return members.entrySet().stream()
        .filter(
            e ->
                e.getKey().source().equals(source)
                    && e.getKey().fingerprint().equals(fingerprint)
                    && e.getValue().contains(blockId))
        .map(e -> e.getKey().pageId())
        .collect(Collectors.toUnmodifiableSet());
  }

  @Override
  public synchronized Set<String> members(
      SourceInstanceId source, String fingerprint, String pageId) {
    return members.getOrDefault(new Key(source, fingerprint, pageId), Set.of());
  }

  @Override
  public synchronized void recordDiscovery(
      SourceInstanceId source, RunId run, Discovery discovery) {
    discoveries.putIfAbsent(
        new DiscoveryKey(source, run, discovery.resourceId(), discovery.stateKey()), discovery);
    comparisons.remove(
        new ComparisonKey(source, run, discovery.fingerprint(), discovery.resourceId()));
  }

  @Override
  public synchronized List<Discovery> discoveries(SourceInstanceId source, RunId run) {
    return discoveries.entrySet().stream()
        .filter(entry -> entry.getKey().source().equals(source) && entry.getKey().run().equals(run))
        .map(Map.Entry::getValue)
        .toList();
  }

  @Override
  public synchronized void beginComparison(
      SourceInstanceId source, RunId run, Comparison comparison) {
    comparisons.putIfAbsent(
        new ComparisonKey(source, run, comparison.fingerprint(), comparison.pageId()), comparison);
  }

  @Override
  public synchronized Optional<Comparison> comparison(
      SourceInstanceId source, RunId run, String fingerprint, String pageId) {
    return Optional.ofNullable(
        comparisons.get(new ComparisonKey(source, run, fingerprint, pageId)));
  }
}
