package io.scos.client.source;

import io.scos.client.ClientJson;
import io.scos.spi.connector.RejectedItem;
import io.scos.spi.connector.RejectedItem.FailureKind;
import java.time.Instant;
import java.util.Optional;

/** 원문을 싣지 않는 원천 거절 값의 생성기. */
public record Rejections(Instant observedAt) {
  public RejectedItem of(FailureKind kind, String message, String externalId) {
    return new RejectedItem(
        kind,
        message,
        Optional.of(ClientJson.MAPPER.createObjectNode().put("externalId", externalId).toString()),
        observedAt,
        Optional.empty());
  }
}
