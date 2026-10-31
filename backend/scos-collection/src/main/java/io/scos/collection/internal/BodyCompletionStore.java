package io.scos.collection.internal;

import io.scos.collection.internal.ReconciliationDiscovery.Comparison;
import io.scos.collection.internal.ReconciliationDiscovery.Discovery;
import io.scos.spi.connector.ReconciliationContext.Path;
import io.scos.spi.connector.SourceInstanceId;
import io.scos.spi.connector.SourceOrder;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** 본문 완료의 저장 경계. 호출자의 수집 트랜잭션에 참여한다. */
public interface BodyCompletionStore {
  record Reading(Path path, Instant at) {}

  record Body(
      String fingerprint,
      String pageId,
      String version,
      SourceOrder order,
      Instant completedAt,
      Set<String> blocks,
      Optional<RunId> completedRun,
      Optional<Reading> lastReading) {
    public Body(
        String fingerprint,
        String pageId,
        String version,
        SourceOrder order,
        Instant completedAt,
        Set<String> blocks,
        Optional<RunId> completedRun) {
      this(
          fingerprint, pageId, version, order, completedAt, blocks, completedRun, Optional.empty());
    }

    public Body readAt(Path path, Instant at) {
      return new Body(
          fingerprint,
          pageId,
          version,
          order,
          completedAt,
          blocks,
          completedRun,
          Optional.of(new Reading(path, at)));
    }

    public Body(
        String fingerprint,
        String pageId,
        String version,
        SourceOrder order,
        Instant completedAt,
        Set<String> blocks) {
      this(fingerprint, pageId, version, order, completedAt, blocks, Optional.empty());
    }

    public Body {
      blocks = Set.copyOf(blocks);
    }
  }

  record Pending(
      String fingerprint,
      String pageId,
      String version,
      SourceOrder order,
      RunId run,
      Set<String> observed) {
    public Pending {
      observed = Set.copyOf(observed);
    }
  }

  void savePending(SourceInstanceId source, Pending pending);

  Optional<Pending> pending(SourceInstanceId source, String fingerprint, String pageId);

  boolean hasPending(SourceInstanceId source, String fingerprint, RunId run);

  void save(SourceInstanceId source, Body body);

  Optional<Body> find(SourceInstanceId source, String fingerprint, String pageId);

  Set<String> owners(SourceInstanceId source, String fingerprint, String blockId);

  Set<String> members(SourceInstanceId source, String fingerprint, String pageId);

  void recordDiscovery(SourceInstanceId source, RunId run, Discovery discovery);

  List<Discovery> discoveries(SourceInstanceId source, RunId run);

  void beginComparison(SourceInstanceId source, RunId run, Comparison comparison);

  Optional<Comparison> comparison(
      SourceInstanceId source, RunId run, String fingerprint, String pageId);
}
