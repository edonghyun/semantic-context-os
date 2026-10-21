package io.scos.collection.internal;

import io.scos.spi.connector.RawItem;
import io.scos.spi.connector.SourceOrder;
import java.time.Instant;
import java.util.Optional;

/** 저장된 원본 상태(TS RawSourceRecord): active 항목 또는 tombstone. */
public sealed interface RawRecord {
  String externalId();

  Optional<SourceOrder> sourceOrder();

  record Active(RawItem item) implements RawRecord {
    @Override
    public String externalId() {
      return item.externalId();
    }

    @Override
    public Optional<SourceOrder> sourceOrder() {
      return item.sourceOrder();
    }
  }

  record Deleted(
      String externalId,
      Optional<String> resourceType,
      Optional<String> lastSourceVersion,
      Optional<SourceOrder> sourceOrder,
      Instant observedAt,
      Instant deletedAt)
      implements RawRecord {}
}
