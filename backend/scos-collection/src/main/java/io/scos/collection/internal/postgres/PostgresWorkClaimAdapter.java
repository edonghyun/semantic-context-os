package io.scos.collection.internal.postgres;

import io.scos.collection.internal.ClaimLostException;
import io.scos.collection.internal.OwnerId;
import io.scos.collection.internal.WorkClaim;
import io.scos.collection.internal.WorkClaimPort;
import io.scos.spi.connector.SourceInstanceId;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.postgresql.util.PSQLException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 점유를 한 문장(INSERT … ON CONFLICT DO UPDATE … WHERE expires_at <= now RETURNING)으로 처리해 "SELECT 뒤
 * INSERT" 창이 없다. ON CONFLICT DO UPDATE는 행 잠금을 잡으므로 동시 시도는 직렬화되고, WHERE가 거짓인 쪽은 행을 돌려받지 않는다. renew가
 * lock_timeout(55P03)에 걸리면 {@link CannotAcquireLockException}으로 번역한다 — Spring 기본 번역기는 SQLState 55를
 * 분류하지 않아 UncategorizedSQLException이 되므로, heartbeat가 잠금 경합을 타입으로 가를 수 있게 어댑터가 정한다.
 *
 * <p>renew는 자기 트랜잭션에서 {@code SET LOCAL lock_timeout}을 TTL/4로 올려(연결 설정보다 길면 그대로 둔다) 잠금을 기다린다. 쓰기
 * 트랜잭션의 requireActive(FOR UPDATE)가 커밋하면 PostgreSQL 행 잠금 대기열의 다음 차례로 갱신한다 — interval이 TTL/2라 한 번 놓치면
 * 다음 tick이 만료 시각에 걸리기 때문이다(T15.5 heartbeat 2차). 다른 문장의 lock_timeout은 그대로다.
 */
public final class PostgresWorkClaimAdapter implements WorkClaimPort {
  private final JdbcClient jdbc;
  private final TransactionOperations tx;

  public PostgresWorkClaimAdapter(JdbcClient jdbc, TransactionOperations tx) {
    this.jdbc = jdbc;
    this.tx = tx;
  }

  @Override
  public Optional<WorkClaim> tryClaim(
      SourceInstanceId source, OwnerId owner, Duration ttl, Instant now) {
    return jdbc.sql(
            """
            INSERT INTO work_claims (source_instance_id, owner_id, fencing_token, expires_at)
            VALUES (:source, :owner, 1, :expires)
            ON CONFLICT (source_instance_id) DO UPDATE
              SET owner_id = EXCLUDED.owner_id,
                  fencing_token = work_claims.fencing_token + 1,
                  expires_at = EXCLUDED.expires_at
              WHERE work_claims.expires_at <= :now
            RETURNING source_instance_id, owner_id, fencing_token, expires_at
            """)
        .param("source", source.value())
        .param("owner", owner.value())
        .param("expires", Timestamp.from(now.plus(ttl)))
        .param("now", Timestamp.from(now))
        .query(ClaimRow::map)
        .optional();
  }

  @Override
  public WorkClaim renew(WorkClaim claim, Duration ttl, Instant now) {
    try {
      return tx.execute(
          status -> {
            waitForRowLockUpTo(ttl.dividedBy(4));
            return renewRow(claim, ttl, now);
          });
    } catch (DataAccessException e) {
      if (lockNotAvailable(e))
        throw new CannotAcquireLockException("claim row locked by a writer: " + claim.source(), e);
      throw e;
    }
  }

  /** 이 트랜잭션의 lock_timeout을 wait로 올린다. 연결 설정이 더 길면 그대로 둔다(무제한 0이면 wait가 상한이 된다). */
  private void waitForRowLockUpTo(Duration wait) {
    jdbc.sql(
            """
            SELECT set_config('lock_timeout',
                     greatest(extract(epoch FROM current_setting('lock_timeout')::interval) * 1000, :waitMs)::bigint::text,
                     true)
            """)
        .param("waitMs", wait.toMillis())
        .query(String.class)
        .single();
  }

  private WorkClaim renewRow(WorkClaim claim, Duration ttl, Instant now) {
    return jdbc.sql(
            """
            UPDATE work_claims SET expires_at = :expires
            WHERE source_instance_id = :source AND owner_id = :owner AND fencing_token = :token AND expires_at > :now
            RETURNING source_instance_id, owner_id, fencing_token, expires_at
            """)
        .param("expires", Timestamp.from(now.plus(ttl)))
        .param("source", claim.source().value())
        .param("owner", claim.owner().value())
        .param("token", claim.fencingToken())
        .param("now", Timestamp.from(now))
        .query(ClaimRow::map)
        .optional()
        .orElseThrow(() -> new ClaimLostException("claim no longer active: " + claim.source()));
  }

  /** 행 잠금 대기 초과(lock_not_available). */
  static boolean lockNotAvailable(Throwable error) {
    for (Throwable t = error; t != null; t = t.getCause()) {
      if (t instanceof PSQLException p) return "55P03".equals(p.getSQLState());
    }
    return false;
  }

  @Override
  public void release(WorkClaim claim, Instant now) {
    jdbc.sql(
            "UPDATE work_claims SET expires_at = :now WHERE source_instance_id = :source AND owner_id = :owner AND fencing_token = :token")
        .param("now", Timestamp.from(now))
        .param("source", claim.source().value())
        .param("owner", claim.owner().value())
        .param("token", claim.fencingToken())
        .update();
  }

  @Override
  public void requireActive(WorkClaim claim, Instant now) {
    // FOR UPDATE: 같은 소스의 동시 커밋은 여기서 직렬화된다. 호출자 트랜잭션이 없으면 잠금은 즉시 풀리지만 검사 의미는 같다.
    WorkClaim stored =
        jdbc.sql(
                "SELECT source_instance_id, owner_id, fencing_token, expires_at FROM work_claims WHERE source_instance_id = :source FOR UPDATE")
            .param("source", claim.source().value())
            .query(ClaimRow::map)
            .optional()
            .orElseThrow(() -> new ClaimLostException("no claim for " + claim.source()));
    if (stored.fencingToken() != claim.fencingToken() || !stored.isActiveAt(now)) {
      throw new ClaimLostException(
          "claim no longer active: "
              + claim.source()
              + " (stored token "
              + stored.fencingToken()
              + ", given "
              + claim.fencingToken()
              + ")");
    }
  }

  @Override
  public Optional<WorkClaim> current(SourceInstanceId source) {
    return jdbc.sql(
            "SELECT source_instance_id, owner_id, fencing_token, expires_at FROM work_claims WHERE source_instance_id = :source")
        .param("source", source.value())
        .query(ClaimRow::map)
        .optional();
  }

  static final class ClaimRow {
    static WorkClaim map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
      return new WorkClaim(
          new SourceInstanceId(rs.getString("source_instance_id")),
          new OwnerId(rs.getString("owner_id")),
          rs.getLong("fencing_token"),
          rs.getTimestamp("expires_at").toInstant());
    }
  }
}
