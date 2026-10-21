package io.scos.collection.internal.postgres;

import io.scos.collection.internal.ChangeCursor;
import io.scos.collection.internal.SourceChangeLog;
import io.scos.spi.connector.SourceInstanceId;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * F10 어댑터: raw_changes(BIGSERIAL seq) + raw_change_retention(floor). 읽기·정리는 한 트랜잭션. append는 호출자
 * 트랜잭션이고, 그 트랜잭션이 끝날 때까지 쓰기 잠금(advisory)을 쥔다 — seq 순서 = 커밋 순서라 커서 소비자가 늦게 커밋된 낮은 seq를 건너뛰지
 * 않는다(ADR-0021).
 */
public final class PostgresSourceChangeLog implements SourceChangeLog {
  /** raw_changes 쓰기 직렬화 키(트랜잭션 범위 advisory lock). 다른 advisory lock 사용처와 겹치지 않는 상수. */
  static final long APPEND_LOCK_KEY = 0x5C05_0F10L;

  private final JdbcClient jdbc;
  private final TransactionTemplate tx;

  public PostgresSourceChangeLog(JdbcClient jdbc, TransactionTemplate tx) {
    this.jdbc = jdbc;
    this.tx = tx;
  }

  /** 한 INSERT 문장에 담는 행 수(파라미터 수 6 × 이 값이 드라이버 한도 32767 안). */
  static final int ROWS_PER_INSERT = 500;

  @Override
  public void appendAll(List<Entry> entries) {
    if (entries.isEmpty()) return;
    // 커밋까지 다음 append를 막는다 — 호출자가 트랜잭션 마지막에 부르므로 잠금 구간은 이 INSERT와 커밋뿐(ADR-0021)
    jdbc.sql("SELECT pg_advisory_xact_lock(:key)")
        .param("key", APPEND_LOCK_KEY)
        .query((rs, i) -> 1)
        .list();
    for (int from = 0; from < entries.size(); from += ROWS_PER_INSERT) {
      List<Entry> chunk = entries.subList(from, Math.min(entries.size(), from + ROWS_PER_INSERT));
      List<Object> params = new ArrayList<>(chunk.size() * 6);
      for (Entry e : chunk) {
        params.add(e.source().value());
        params.add(e.externalId().orElse(null));
        params.add(e.kind().wire());
        params.add(e.sourceVersion().orElse(null));
        params.add(e.metadataJson().orElse(null));
        params.add(Timestamp.from(e.occurredAt()));
      }
      jdbc.sql(
              "INSERT INTO raw_changes (source_instance_id, external_id, kind, source_version, metadata, occurred_at) VALUES "
                  + String.join(
                      ", ", Collections.nCopies(chunk.size(), "(?, ?, ?, ?, CAST(? AS jsonb), ?)")))
          .params(params)
          .update();
    }
  }

  @Override
  public Page read(Optional<SourceInstanceId> source, long after, int limit) {
    return tx.execute(
        status -> {
          long floor = floor();
          long head = headSeq(floor);
          ChangeCursor.validate(after, limit, floor, head);
          String sql =
              "SELECT seq, source_instance_id, external_id, kind, source_version, metadata::text AS metadata, occurred_at FROM raw_changes WHERE seq > :after"
                  + (source.isPresent() ? " AND source_instance_id = :source" : "")
                  + " ORDER BY seq LIMIT :limit";
          var spec = jdbc.sql(sql).param("after", after).param("limit", limit);
          if (source.isPresent()) spec = spec.param("source", source.get().value());
          List<Change> changes =
              spec.query(
                      (rs, i) ->
                          new Change(
                              rs.getLong("seq"),
                              new SourceInstanceId(rs.getString("source_instance_id")),
                              Optional.ofNullable(rs.getString("external_id")),
                              Kind.fromWire(rs.getString("kind")),
                              Optional.ofNullable(rs.getString("source_version")),
                              Optional.ofNullable(rs.getString("metadata")),
                              rs.getTimestamp("occurred_at").toInstant()))
                  .list();
          long next = changes.isEmpty() ? head : changes.get(changes.size() - 1).seq();
          return new Page(changes, next, head);
        });
  }

  @Override
  public long head() {
    return headSeq(floor());
  }

  @Override
  public int pruneBefore(Instant cutoff) {
    Integer pruned =
        tx.execute(
            status -> {
              Long through =
                  jdbc.sql(
                          "SELECT coalesce(max(seq), 0) FROM raw_changes WHERE occurred_at < :cutoff")
                      .param("cutoff", Timestamp.from(cutoff))
                      .query(Long.class)
                      .single();
              if (through == 0) return 0;
              int deleted =
                  jdbc.sql("DELETE FROM raw_changes WHERE seq <= :through")
                      .param("through", through)
                      .update();
              // 보존 행은 V13이 넣는다(id 1) — 하한은 올라가기만 한다
              int raised =
                  jdbc.sql(
                          "UPDATE raw_change_retention SET floor = GREATEST(floor, :through) WHERE id = 1")
                      .param("through", through)
                      .update();
              if (raised != 1)
                throw new IllegalStateException("raw_change_retention row (V13 seed) is missing");
              return deleted;
            });
    return pruned == null ? 0 : pruned;
  }

  /** 보존 하한(V13 시드 행, 마이그레이션 직후 0). 행이 없으면 실패한다 — 운영에서 생기지 않는 상태다. */
  private long floor() {
    return jdbc.sql("SELECT floor FROM raw_change_retention WHERE id = 1")
        .query(Long.class)
        .single();
  }

  private long headSeq(long floor) {
    return jdbc.sql("SELECT GREATEST(coalesce(max(seq), 0), :floor) FROM raw_changes")
        .param("floor", floor)
        .query(Long.class)
        .single();
  }
}
