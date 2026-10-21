package io.scos.collection.internal.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * blob FK 마이그레이션(V11)이 이전 코드가 남긴 고아 blob 참조를 가진 DB에도 적용된다(ADR-0018 §11). 공용 컨테이너 안에 별도 데이터베이스를 만들어
 * V10까지 적용 → 고아 참조 삽입 → 전체 migrate.
 */
@DisplayName("blob FK 마이그레이션(V11)은 고아 blob 참조가 있어도 적용된다(ADR-0018 §11)")
@Tag("db")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BlobFkMigrationTest {
  static final String DB = "scos_orphan_blob";

  JdbcClient jdbc;

  /** V10까지 적용한 DB에 blob 없는 콘텐츠·격리 참조를 넣고 전체 마이그레이션한다. */
  @BeforeAll
  void migrateOverOrphanBlobReferences() {
    MigrationRehearsal rehearsal = MigrationRehearsal.freshDatabase(DB).migrateTo("10");
    jdbc = rehearsal.jdbc();
    jdbc.sql(
            "INSERT INTO raw_items (source_instance_id, external_id, source_version, metadata, observed_at, content_storage, content_media_type, content_sha256, content_size)"
                + " VALUES ('src', 'i1', 'v1', '{}', now(), 'blob', 'application/json', 'deadbeef', 10)")
        .update();
    jdbc.sql(
            "INSERT INTO raw_rejections (run_id, source_instance_id, captured_at, observed_at, failure_kind, message, quarantined_storage, quarantined_media_type, quarantined_sha256, quarantined_size)"
                + " VALUES ('r1', 'src', now(), now(), 'invalid_item', 'no ts', 'blob', 'application/json', 'deadbeef', 10)")
        .update();
    rehearsal.migrateToLatest();
  }

  @Test
  @DisplayName("고아 콘텐츠 참조는 NULL로 정리된다")
  void clearsOrphanContentReference() {
    // then: 행이 지워져도 값 조회는 비므로 "행이 남고 참조만 NULL"인 행 수를 센다
    assertThat(
            count(
                "SELECT count(*) FROM raw_items WHERE external_id = 'i1' AND content_storage IS NULL AND content_sha256 IS NULL"))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("고아 격리 참조는 NULL로 정리된다")
  void clearsOrphanQuarantinedReference() {
    // then
    assertThat(
            count(
                "SELECT count(*) FROM raw_rejections WHERE run_id = 'r1' AND quarantined_storage IS NULL AND quarantined_sha256 IS NULL"))
        .isEqualTo(1);
  }

  int count(String sql) {
    return jdbc.sql(sql).query(Integer.class).single();
  }

  @Test
  @DisplayName("blob FK 둘 다 검증 완료 상태가 된다")
  void validatesBothForeignKeys() {
    // then
    assertThat(
            count(
                "SELECT count(*) FROM pg_constraint WHERE conname IN ('raw_items_content_blob_fk', 'raw_rejections_quarantined_blob_fk') AND convalidated"))
        .isEqualTo(2);
  }
}
