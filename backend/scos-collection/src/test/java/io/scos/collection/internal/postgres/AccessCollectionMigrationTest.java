package io.scos.collection.internal.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

@Tag("db")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("V27 데이터는 접근 수집 마이그레이션 뒤에도 보존된다")
class AccessCollectionMigrationTest {
  JdbcClient jdbc;

  @BeforeAll
  void migrate() {
    var rehearsal = MigrationRehearsal.freshDatabase("scos_access_rehearsal").migrateTo("27");
    jdbc = rehearsal.jdbc();
    jdbc.sql(
            "INSERT INTO raw_items (source_instance_id, external_id, source_version, metadata, observed_at) VALUES ('drive-a', 'file-1', 'v1', '{\"name\":\"Plan\"}', '2026-10-14T00:00:00Z')")
        .update();
    jdbc.sql(
            "INSERT INTO collection_runs (run_id, source_instance_id, status, started_at) VALUES ('old-run', 'drive-a', 'running', '2026-10-14T00:00:00Z')")
        .update();
    jdbc.sql(
            "INSERT INTO users VALUES ('alice', 'alice@example.com', true, 'member', '2026-10-14T00:00:00Z')")
        .update();
    jdbc.sql(
            "INSERT INTO user_tokens VALUES ('synthetic-hash', 'alice', 'fixture', '2026-10-14T00:00:00Z', NULL, NULL)")
        .update();
    rehearsal.migrateToLatest();
  }

  @Test
  @DisplayName("기존 원본 메타데이터가 보존된다")
  void preservesRawItem() {
    // then
    assertThat(
            jdbc.sql("SELECT metadata FROM raw_items WHERE external_id = 'file-1'")
                .query(String.class)
                .single())
        .isEqualTo("{\"name\":\"Plan\"}");
  }

  @Test
  @DisplayName("기존 run 본문 상태가 보존된다")
  void preservesRun() {
    // then
    assertThat(
            jdbc.sql("SELECT status FROM collection_runs WHERE run_id = 'old-run'")
                .query(String.class)
                .single())
        .isEqualTo("running");
  }

  @Test
  @DisplayName("V23 사용자가 보존된다")
  void preservesUser() {
    // then
    assertThat(jdbc.sql("SELECT email FROM users WHERE id = 'alice'").query(String.class).single())
        .isEqualTo("alice@example.com");
  }

  @Test
  @DisplayName("V27 합성 토큰 소유자가 보존된다")
  void preservesToken() {
    // then
    assertThat(
            jdbc.sql("SELECT user_id FROM user_tokens WHERE token_hash = 'synthetic-hash'")
                .query(String.class)
                .single())
        .isEqualTo("alice");
  }

  @Test
  @DisplayName("기존 run 접근 결과는 skipped와 NULL 시각이다")
  void existingRunIsSkipped() {
    // then
    assertThat(
            jdbc.sql(
                    "SELECT count(*) FROM collection_runs WHERE run_id = 'old-run' AND access_result = 'skipped' AND access_finished_at IS NULL")
                .query(Integer.class)
                .single())
        .isEqualTo(1);
  }

  @Test
  @DisplayName("ACL 초기 행은 없다")
  void startsWithNoAcl() {
    // then
    assertThat(jdbc.sql("SELECT count(*) FROM raw_acl").query(Integer.class).single()).isEqualTo(0);
  }

  @Test
  @DisplayName("그룹과 신원 관측 초기 행은 없다")
  void startsWithNoObservations() {
    // then
    assertThat(jdbc.sql("SELECT count(*) FROM access_observations").query(Integer.class).single())
        .isEqualTo(0);
  }

  @Test
  @DisplayName("알 수 없는 접근 결과는 DB가 거부한다")
  void rejectsInvalidAccessResult() {
    // then
    assertThatThrownBy(
            () ->
                jdbc.sql(
                        "UPDATE collection_runs SET access_result = 'invalid' WHERE run_id = 'old-run'")
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("알 수 없는 ACL 상태는 DB가 거부한다")
  void rejectsInvalidAclStatus() {
    // then
    assertThatThrownBy(
            () ->
                jdbc.sql(
                        "INSERT INTO raw_acl VALUES ('drive-a', 'bad', 'invalid', '{}', 'f', now())")
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("알 수 없는 관측 종류는 DB가 거부한다")
  void rejectsInvalidObservationKind() {
    // then
    assertThatThrownBy(
            () ->
                jdbc.sql(
                        "INSERT INTO access_observations (run_id, kind, key, payload, observed_at) VALUES ('old-run', 'invalid', 'k', '{}', now())")
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("없는 run의 관측은 DB가 거부한다")
  void rejectsOrphanObservation() {
    // then
    assertThatThrownBy(
            () ->
                jdbc.sql(
                        "INSERT INTO access_observations (run_id, kind, key, payload, observed_at) VALUES ('missing-run', 'group', 'k', '{}', now())")
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("관측 상충 기본값은 false다")
  void conflictDefaultsToFalse() {
    jdbc.sql(
            "INSERT INTO access_observations (run_id, kind, key, payload, observed_at) VALUES ('old-run', 'group', 'default-conflict', '{}', now())")
        .update();
    // then
    assertThat(
            jdbc.sql("SELECT conflicted FROM access_observations WHERE key = 'default-conflict'")
                .query(Boolean.class)
                .single())
        .isFalse();
    jdbc.sql("DELETE FROM access_observations WHERE key = 'default-conflict'").update();
  }

  @Test
  @DisplayName("run 삭제는 그 run의 접근 관측도 지운다")
  void runDeletionCascades() {
    jdbc.sql(
            "INSERT INTO collection_runs (run_id, source_instance_id, status, started_at) VALUES ('deleted-run', 'drive-a', 'running', now())")
        .update();
    jdbc.sql(
            "INSERT INTO access_observations (run_id, kind, key, payload, observed_at) VALUES ('deleted-run', 'identity', 'alice', '{}', now())")
        .update();
    jdbc.sql("DELETE FROM collection_runs WHERE run_id = 'deleted-run'").update();
    // then
    assertThat(
            jdbc.sql("SELECT count(*) FROM access_observations WHERE run_id = 'deleted-run'")
                .query(Integer.class)
                .single())
        .isZero();
  }
}
