package io.scos.collection.internal;

import io.scos.spi.connector.RawItem;
import io.scos.spi.connector.SourceOrder;
import java.time.Instant;
import java.util.Optional;

/** 저장된 원본 상태: active 항목 또는 tombstone. */
public sealed interface RawRecord {
  String externalId();

  Optional<SourceOrder> sourceOrder();

  /** 알려진 리소스 종류(tombstone은 삭제 전 종류를 지닌다). */
  Optional<String> resourceType();

  /** 마지막으로 알려진 원본 버전. */
  Optional<String> lastSourceVersion();

  record Active(RawItem item) implements RawRecord {
    @Override
    public String externalId() {
      return item.externalId();
    }

    @Override
    public Optional<SourceOrder> sourceOrder() {
      return item.sourceOrder();
    }

    @Override
    public Optional<String> resourceType() {
      return Optional.ofNullable(item.resourceType());
    }

    @Override
    public Optional<String> lastSourceVersion() {
      return Optional.of(item.sourceVersion());
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
