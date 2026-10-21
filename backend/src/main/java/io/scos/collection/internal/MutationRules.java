package io.scos.collection.internal;

import io.scos.spi.connector.RawMutation;
import io.scos.spi.connector.SourceOrder;
import java.util.Optional;

/** F4 순서 규칙(TS isStaleMutation) — 메모리·PostgreSQL 어댑터가 공유한다. */
public final class MutationRules {
  private MutationRules() {}

  /** 기존 순서가 있고 들어온 순서가 없거나 같거나 과거면 stale. scheme이 다르면 계약 위반. */
  public static boolean isStale(Optional<RawRecord> existing, Optional<SourceOrder> incoming) {
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

  /** delete 적용 결과 tombstone(TS applyMutation delete 분기). */
  public static RawRecord.Deleted tombstone(Optional<RawRecord> existing, RawMutation.Delete d) {
    Optional<String> resourceType =
        existing.map(
            r ->
                r instanceof RawRecord.Active a
                    ? a.item().resourceType()
                    : ((RawRecord.Deleted) r).resourceType().orElse(null));
    Optional<String> lastVersion =
        d.sourceVersion()
            .or(
                () ->
                    existing.flatMap(
                        r ->
                            r instanceof RawRecord.Active a
                                ? Optional.of(a.item().sourceVersion())
                                : ((RawRecord.Deleted) r).lastSourceVersion()));
    return new RawRecord.Deleted(
        d.externalId(),
        resourceType,
        lastVersion,
        d.sourceOrder(),
        d.observedAt(),
        d.occurredAt().orElse(d.observedAt()));
  }
}
