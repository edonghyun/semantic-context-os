package io.scos.collection.internal;

import io.scos.spi.connector.SourceInstanceId;
import java.time.Instant;

/**
 * 한 소스에 대한 점유. fencing token은 점유가 바뀔 때마다 단조 증가하며, 저장 경계가 오래된 token의 쓰기를 거부하는 근거다(불변식 5). 만료 시각은 점유
 * 시 받은 시계 기준이다.
 */
public record WorkClaim(
    SourceInstanceId source, OwnerId owner, long fencingToken, Instant expiresAt) {
  public boolean isActiveAt(Instant now) {
    return expiresAt.isAfter(now);
  }
}
