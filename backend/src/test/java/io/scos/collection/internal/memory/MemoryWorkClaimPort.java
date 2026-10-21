package io.scos.collection.internal.memory;

import io.scos.collection.internal.ClaimLostException;
import io.scos.collection.internal.OwnerId;
import io.scos.collection.internal.WorkClaim;
import io.scos.collection.internal.WorkClaimPort;
import io.scos.spi.connector.SourceInstanceId;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** 메모리 참조 구현. 모든 연산은 저장소 전체를 잠근다 — 계약의 "하나만 성공"을 가장 단순하게 만족한다. */
public class MemoryWorkClaimPort implements WorkClaimPort {
  private final Map<SourceInstanceId, WorkClaim> claims = new HashMap<>();

  @Override
  public synchronized Optional<WorkClaim> tryClaim(
      SourceInstanceId source, OwnerId owner, Duration ttl, Instant now) {
    WorkClaim existing = claims.get(source);
    if (existing != null && existing.isActiveAt(now)) return Optional.empty();
    long token = existing == null ? 1 : existing.fencingToken() + 1;
    WorkClaim claim = new WorkClaim(source, owner, token, now.plus(ttl));
    claims.put(source, claim);
    return Optional.of(claim);
  }

  @Override
  public synchronized WorkClaim renew(WorkClaim claim, Duration ttl, Instant now) {
    WorkClaim stored = claims.get(claim.source());
    if (!isSameHolder(stored, claim) || !stored.isActiveAt(now))
      throw new ClaimLostException("claim no longer active: " + claim.source());
    WorkClaim renewed =
        new WorkClaim(claim.source(), claim.owner(), claim.fencingToken(), now.plus(ttl));
    claims.put(claim.source(), renewed);
    return renewed;
  }

  @Override
  public synchronized void release(WorkClaim claim, Instant now) {
    WorkClaim stored = claims.get(claim.source());
    if (isSameHolder(stored, claim))
      claims.put(
          claim.source(), new WorkClaim(claim.source(), claim.owner(), claim.fencingToken(), now));
  }

  @Override
  public synchronized void requireActive(WorkClaim claim, Instant now) {
    WorkClaim stored = claims.get(claim.source());
    if (stored == null
        || stored.fencingToken() != claim.fencingToken()
        || !stored.isActiveAt(now)) {
      throw new ClaimLostException("claim no longer active: " + claim.source());
    }
  }

  @Override
  public synchronized Optional<WorkClaim> current(SourceInstanceId source) {
    return Optional.ofNullable(claims.get(source));
  }

  private static boolean isSameHolder(WorkClaim stored, WorkClaim claim) {
    return stored != null
        && stored.owner().equals(claim.owner())
        && stored.fencingToken() == claim.fencingToken();
  }
}
