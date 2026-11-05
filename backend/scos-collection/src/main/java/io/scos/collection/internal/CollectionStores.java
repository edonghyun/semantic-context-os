package io.scos.collection.internal;

import java.util.Optional;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 한 트랜잭션을 함께 쓰는 수집 포트 묶음. 페이지 커밋과 run 완료가 이 위에서 원자적이다 — 원자성은 어댑터가 같은 DataSource를 공유하는 조립의
 * 성질이다(DESIGN-PATTERNS §10).
 */
public record CollectionStores(
    TransactionOperations tx,
    WorkClaimPort claims,
    RunStore runs,
    RawItemStore rawItems,
    CheckpointPort checkpoints,
    OutboxPort outbox,
    SourceChangeLog changes,
    RawAclStore rawAcls,
    AccessObservationStore accessObservations,
    Optional<BodyCompletionStore> bodies,
    Optional<SourceInstanceStore> sources) {
  public CollectionStores(
      TransactionOperations tx,
      WorkClaimPort claims,
      RunStore runs,
      RawItemStore rawItems,
      CheckpointPort checkpoints,
      OutboxPort outbox,
      SourceChangeLog changes,
      RawAclStore rawAcls,
      AccessObservationStore accessObservations) {
    this(
        tx,
        claims,
        runs,
        rawItems,
        checkpoints,
        outbox,
        changes,
        rawAcls,
        accessObservations,
        Optional.empty(),
        Optional.empty());
  }

  public CollectionStores(
      TransactionOperations tx,
      WorkClaimPort claims,
      RunStore runs,
      RawItemStore rawItems,
      CheckpointPort checkpoints,
      OutboxPort outbox,
      SourceChangeLog changes,
      RawAclStore rawAcls,
      AccessObservationStore accessObservations,
      Optional<BodyCompletionStore> bodies) {
    this(
        tx,
        claims,
        runs,
        rawItems,
        checkpoints,
        outbox,
        changes,
        rawAcls,
        accessObservations,
        bodies,
        Optional.empty());
  }

  public CollectionStores withSources(SourceInstanceStore sources) {
    return new CollectionStores(
        tx,
        claims,
        runs,
        rawItems,
        checkpoints,
        outbox,
        changes,
        rawAcls,
        accessObservations,
        bodies,
        Optional.of(sources));
  }

  public CollectionStores withBodies(BodyCompletionStore bodies) {
    return new CollectionStores(
        tx,
        claims,
        runs,
        rawItems,
        checkpoints,
        outbox,
        changes,
        rawAcls,
        accessObservations,
        Optional.of(bodies),
        sources);
  }

  /** 투영 요청 포트만 바꾼 묶음. */
  public CollectionStores withOutbox(OutboxPort outbox) {
    return new CollectionStores(
        tx,
        claims,
        runs,
        rawItems,
        checkpoints,
        outbox,
        changes,
        rawAcls,
        accessObservations,
        bodies,
        sources);
  }

  /** 변경 로그만 바꾼 묶음. */
  public CollectionStores withChanges(SourceChangeLog changes) {
    return new CollectionStores(
        tx,
        claims,
        runs,
        rawItems,
        checkpoints,
        outbox,
        changes,
        rawAcls,
        accessObservations,
        bodies,
        sources);
  }
}
