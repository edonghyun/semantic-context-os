package io.scos.collection.internal.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 공용 DB 준비 어휘 {@link PostgresSupport#truncateAll()} 자체 테스트(F15 C7): 모든 DB 테스트가 "마이그레이션 직후와 같은 상태"에서
 * 시작한다는 전제가 이것에 기댄다. 시드 목록 대조는 MigrationSeedsTest가 본다.
 */
@DisplayName("truncateAll은 DB를 마이그레이션 직후 상태로 되돌린다(C7)")
@Tag("db")
class PostgresSupportTest {
  final JdbcClient jdbc = JdbcClient.create(PostgresSupport.dataSource());

  /** Flyway 이력을 뺀 public 테이블마다 행 수(행이 있는 것만). */
  Map<String, Integer> nonEmptyTables() {
    List<String> tables =
        jdbc.sql(
                "SELECT tablename FROM pg_tables WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history' ORDER BY tablename")
            .query(String.class)
            .list();
    Map<String, Integer> out = new LinkedHashMap<>();
    for (String t : tables) {
      int rows = jdbc.sql("SELECT count(*) FROM \"" + t + "\"").query(Integer.class).single();
      if (rows > 0) out.put(t, rows);
    }
    return out;
  }

  @Nested
  @DisplayName("행과 바뀐 시드가 있는 DB를 truncateAll하면")
  class AfterTruncate {
    @Test
    @DisplayName("시드 행 하나(raw_change_retention floor 0) 말고는 모든 테이블이 빈다")
    void leavesOnlySeed() {
      // given
      jdbc.sql(
              "INSERT INTO work_claims (source_instance_id, owner_id, fencing_token, expires_at) VALUES ('s', 'w', 1, now())")
          .update();
      jdbc.sql("UPDATE raw_change_retention SET floor = 42").update();
      if (nonEmptyTables().get("work_claims") == null)
        throw new IllegalStateException("given 준비 실패: work_claims에 행을 넣지 못했다");

      // when
      PostgresSupport.truncateAll();

      // then
      assertThat(nonEmptyTables()).containsExactly(Map.entry("raw_change_retention", 1));
    }

    @Test
    @DisplayName("시드 행은 마이그레이션 값(floor 0)으로 돌아온다")
    void restoresSeedValue() {
      // given
      jdbc.sql("UPDATE raw_change_retention SET floor = 42").update();

      // when
      PostgresSupport.truncateAll();

      // then
      assertThat(jdbc.sql("SELECT floor FROM raw_change_retention").query(Long.class).single())
          .isZero();
    }
  }
}
