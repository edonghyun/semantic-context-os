package io.scos.collection.internal;

import io.scos.spi.connector.RawItem;
import io.scos.spi.connector.RawMutation;
import io.scos.spi.connector.SourceOrder;
import java.util.Optional;

/**
 * F4 적용 규칙이 정한 쓰기 하나 — 메모리·PostgreSQL 어댑터는 이 결정대로 저장만 한다. 규칙은 {@link RawItemStore#apply}의 설명과 같다.
 */
public sealed interface MutationDecision {
  RawItemStore.Outcome outcome();

  /** 저장된 것을 그대로 둔다. */
  record Unchanged() implements MutationDecision {
    @Override
    public RawItemStore.Outcome outcome() {
      return RawItemStore.Outcome.UNCHANGED;
    }
  }

  /** active 항목을 쓴다(tombstone 복원 포함). */
  record WriteActive(RawItem item) implements MutationDecision {
    @Override
    public RawItemStore.Outcome outcome() {
      return RawItemStore.Outcome.UPSERTED;
    }
  }

  /** tombstone을 쓴다(없던 키도). */
  record WriteTombstone(RawRecord.Deleted tombstone) implements MutationDecision {
    @Override
    public RawItemStore.Outcome outcome() {
      return RawItemStore.Outcome.DELETED;
    }
  }

  static MutationDecision of(Optional<RawRecord> existing, RawMutation mutation) {
    return switch (mutation) {
      case RawMutation.Upsert u -> {
        if (isStale(existing, u.item().sourceOrder())) yield new Unchanged();
        if (existing.orElse(null) instanceof RawRecord.Active a
            && a.item().sourceVersion().equals(u.item().sourceVersion())) yield new Unchanged();
        yield new WriteActive(u.item());
      }
      case RawMutation.Delete d -> {
        if (isStale(existing, d.sourceOrder())) yield new Unchanged();
        yield new WriteTombstone(tombstone(existing, d));
      }
    };
  }

  /** 기존 순서가 있고 들어온 순서가 없거나 같거나 과거면 stale. scheme이 다르면 계약 위반. */
  private static boolean isStale(Optional<RawRecord> existing, Optional<SourceOrder> incoming) {
    if (existing.isEmpty()) return false;
    Optional<SourceOrder> current = existing.get().sourceOrder();
    if (current.isEmpty()) return false;
    if (incoming.isEmpty()) return true;
    if (!current.get().scheme().equals(incoming.get().scheme()))
      throw new IllegalArgumentException(
          "Source mutation order scheme conflict: current "
              + current.get().scheme()
              + ", incoming "
              + incoming.get().scheme());
    return incoming.get().position().compareTo(current.get().position()) <= 0;
  }

  /** 주어진 버전이 없으면 기존 버전을, 삭제 시각이 없으면 관측 시각을 쓴다. */
  private static RawRecord.Deleted tombstone(Optional<RawRecord> existing, RawMutation.Delete d) {
    return new RawRecord.Deleted(
        d.externalId(),
        existing.flatMap(RawRecord::resourceType),
        d.sourceVersion().or(() -> existing.flatMap(RawRecord::lastSourceVersion)),
        d.sourceOrder(),
        d.observedAt(),
        d.occurredAt().orElse(d.observedAt()));
  }
}
