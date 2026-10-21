package io.scos.collection.internal.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;

import io.scos.boot.PostgresCollectionConfiguration;
import io.scos.collection.internal.RawRecord;
import io.scos.collection.internal.StoredCheckpoint;
import io.scos.collection.internal.WorkClaim;
import io.scos.spi.connector.SourceInstanceId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 이전 리허설(ADR-0020 §6): 합성 데이터로 V1 형태 → V4 형태의 행을 넣은 데이터베이스를 최신까지 한 번 올리고(@BeforeAll), 행 보존·새 열
 * 기본값·FK 검증·최신 어댑터 읽기를 계약별로 본다(TESTING §2.5 규칙 6). 실데이터·운영 규모 데이터 없음.
 */
@Tag("db")
@DisplayName("옛 형태의 행은 최신 마이그레이션 뒤에도 보존되고 읽힌다(ADR-0020 §6)")
class MigrationRehearsalTest {
  static final String DB = "scos_rehearsal";
  static final SourceInstanceId SOURCE = new SourceInstanceId("src-v1");

  MigrationRehearsal rehearsal;
  JdbcClient jdbc;

  @Nested
  @TestInstance(PER_CLASS)
  @DisplayName("V1·V4 형태의 행이 있는 DB를 최신으로 올리면")
  class MigratedToLatest {
    @BeforeAll
    void rehearse() {
      rehearsal = MigrationRehearsal.freshDatabase(DB).migrateTo("1");
      jdbc = rehearsal.jdbc();
      givenV1ShapedRows();
      rehearsal.migrateTo("4");
      givenV4ShapedRows();
      rehearsal.migrateToLatest();
    }

    @Test
    @DisplayName("최신 스키마 validate가 통과한다")
    void validates() {
      // when·then
      assertThatCode(rehearsal::validate).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("검증되지 않은 FK가 없다")
    void foreignKeysAreValidated() {
      // then
      assertThat(
              count("SELECT count(*) FROM pg_constraint WHERE contype = 'f' AND NOT convalidated"))
          .isZero();
    }

    @Test
    @DisplayName("V8: raw_items.body 값은 metadata 열로 옮겨진다")
    void bodyRenamedToMetadata() {
      // then
      assertThat(text("SELECT metadata::text FROM raw_items WHERE external_id = 'i1'"))
          .isEqualTo("{\"k\": 1}");
    }

    @Test
    @DisplayName("V1 원본 행은 active 상태다")
    void rawItemIsActive() {
      // then
      assertThat(text("SELECT state FROM raw_items WHERE external_id = 'i1'")).isEqualTo("active");
    }

    @Test
    @DisplayName("V8·V10·V12: 투영 요청의 새 열은 기본값이다")
    void projectionRequestDefaults() {
      // then
      assertThat(
              text(
                  "SELECT attempts || '/' || version || '/' || parked || '/' || consecutive_failures"
                      + " FROM projection_requests"))
          .isEqualTo("0/0/false/0");
    }

    @Test
    @DisplayName("V5: unit_texts.ctext는 빈 값이다")
    void ctextDefault() {
      // then
      assertThat(text("SELECT ctext FROM unit_texts WHERE unit_id = 'u1'")).isEmpty();
    }

    @Test
    @DisplayName("V7: unit_vectors.partition은 /knowledge다")
    void vectorPartitionDefault() {
      // then
      assertThat(text("SELECT partition FROM unit_vectors WHERE unit_id = 'u1'"))
          .isEqualTo("/knowledge");
    }

    @Test
    @DisplayName("V4 bigram 행이 보존된다")
    void bigramsKept() {
      // then
      assertThat(count("SELECT count(*) FROM unit_bigrams")).isEqualTo(1);
    }

    @Nested
    @DisplayName("최신 어댑터로 옛 행을 읽으면")
    class LatestAdapters {
      PostgresCollectionConfiguration.Adapters adapters() {
        return PostgresCollectionConfiguration.adapters(rehearsal.dataSource());
      }

      @Test
      @DisplayName("V1 원본 행 하나를 센다")
      void countsRawItem() {
        // when
        int count = adapters().rawItems().count(SOURCE);

        // then
        assertThat(count).isEqualTo(1);
      }

      @Test
      @DisplayName("V14: resource_type이 NULL인 V1 행은 unknown으로 읽힌다")
      void nullResourceTypeReadsAsUnknown() {
        // when
        Optional<RawRecord> record = adapters().rawItems().get(SOURCE, "i1");

        // then
        assertThat(record)
            .get()
            .isInstanceOfSatisfying(
                RawRecord.Active.class,
                active -> assertThat(active.item().resourceType()).isEqualTo("unknown"));
      }

      @Test
      @DisplayName("V1 체크포인트의 revision을 읽는다")
      void readsCheckpoint() {
        // when
        StoredCheckpoint checkpoint = adapters().checkpoints().read(SOURCE);

        // then
        assertThat(checkpoint.revision()).isEqualTo(2);
      }

      @Test
      @DisplayName("V1 점유를 읽는다")
      void readsClaim() {
        // when
        Optional<WorkClaim> claim = adapters().claims().current(SOURCE);

        // then
        assertThat(claim).isPresent();
      }

      @Test
      @DisplayName("V1 투영 요청이 대기 중이다")
      void readsPendingRequest() {
        // when
        List<SourceInstanceId> pending = adapters().outbox().pending();

        // then
        assertThat(pending).containsExactly(SOURCE);
      }

      @Test
      @DisplayName("V13 변경 로그는 비어 시작한다")
      void changeLogStartsEmpty() {
        // when
        long head = adapters().changes().head();

        // then
        assertThat(head).isZero();
      }

      @Test
      @DisplayName("V8 run 기록은 비어 시작한다")
      void runsStartEmpty() {
        // when
        var latest = adapters().runs().latest(SOURCE);

        // then
        assertThat(latest).isEmpty();
      }
    }
  }

  // ---- 리허설 준비 ----

  void givenV1ShapedRows() {
    jdbc.sql(
            "INSERT INTO work_claims VALUES ('src-v1', 'old-host:1', 7, now() + interval '1 hour')")
        .update();
    jdbc.sql("INSERT INTO source_checkpoints VALUES ('src-v1', 2, 1, '{\"after\":\"x\"}', now())")
        .update();
    jdbc.sql("INSERT INTO raw_items VALUES ('src-v1', 'i1', 'v1', '{\"k\":1}', now())").update();
    jdbc.sql("INSERT INTO projection_requests VALUES ('src-v1', now())").update();
  }

  void givenV4ShapedRows() {
    jdbc.sql(
            "INSERT INTO units (space_id, id, document_id, partition, title, content, content_hash, tokens, tier, source_id, uri, captured_at, importance)"
                + " VALUES ('legacy', 'u1', 'd1', '/knowledge', 't', '정산 규정', 'h', 3, 'raw', 's', 'uri://d1', '2026-10-01T00:00:00Z', 1)")
        .update();
    jdbc.sql(
            "INSERT INTO unit_texts (space_id, unit_id, document_id, text) VALUES ('legacy', 'u1', 'd1', '정산 규정')")
        .update();
    jdbc.sql(
            "INSERT INTO unit_bigrams (space_id, unit_seq, bigram, cnt) SELECT 'legacy', seq, '정산', 1 FROM unit_texts WHERE unit_id = 'u1'")
        .update();
    jdbc.sql(
            "INSERT INTO unit_vectors (space_id, unit_id, document_id, embedding) VALUES ('legacy', 'u1', 'd1', '[1,2,3]')")
        .update();
  }

  String text(String sql) {
    return jdbc.sql(sql).query(String.class).single();
  }

  int count(String sql) {
    return jdbc.sql(sql).query(Integer.class).single();
  }
}
