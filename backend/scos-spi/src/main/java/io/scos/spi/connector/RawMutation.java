package io.scos.spi.connector;

import java.time.Instant;
import java.util.Optional;

/**
 * 페이지의 변경 한 건(TS RawMutation): upsert 또는 delete. 적용 의미는 수집 런타임의 {@code RawItemStore.apply}가
 * 정한다(F4).
 */
public sealed interface RawMutation {
  String externalId();

  record Upsert(RawItem item) implements RawMutation {
    public Upsert {
      if (item == null) throw new IllegalArgumentException("item");
    }

    @Override
    public String externalId() {
      return item.externalId();
    }
  }

  record Delete(
      String externalId,
      Optional<String> sourceVersion,
      Optional<SourceOrder> sourceOrder,
      Optional<Instant> occurredAt,
      Instant observedAt)
      implements RawMutation {
    public Delete {
      if (externalId == null || externalId.isBlank())
        throw new IllegalArgumentException("externalId");
      if (observedAt == null) throw new IllegalArgumentException("observedAt");
      sourceVersion = sourceVersion == null ? Optional.empty() : sourceVersion;
      sourceOrder = sourceOrder == null ? Optional.empty() : sourceOrder;
      occurredAt = occurredAt == null ? Optional.empty() : occurredAt;
    }
  }
}
