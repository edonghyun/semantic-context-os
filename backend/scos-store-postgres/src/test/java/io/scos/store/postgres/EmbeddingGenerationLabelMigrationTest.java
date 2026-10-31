package io.scos.store.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.collection.internal.postgres.MigrationRehearsal;
import io.scos.engine.EmbeddingGenerationCheck;
import io.scos.spi.embedding.EmbedderIdentity;
import io.scos.spi.embedding.EmbeddingGeneration;
import io.scos.spi.store.SpaceId;
import java.util.List;
import java.util.stream.Stream;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 세대 라벨 마이그레이션(V18, stage2 §3.2): 옛 Gemini 라벨 한 쌍만 새 형식으로 고쳐 쓰고 벡터는 그대로 둔다. 모르는 라벨이 남으면 실패한다. 공용
 * 컨테이너 안에 별도 데이터베이스를 만들어 V17까지 적용 → 그 시점 형태의 행 삽입 → 전체 migrate.
 */
@DisplayName("세대 라벨 마이그레이션(V18)은 옛 Gemini 라벨만 새 형식으로 바꾼다(stage2 §3.2)")
@Tag("db")
class EmbeddingGenerationLabelMigrationTest {
  static final String LEGACY = "GeminiEmbedder:768:title+aliases+content";
  static final String GEMINI = "gemini:gemini-embedding-001:768:title+aliases+content";
  static final String VECTOR = "[0.5,-0.25,2]";

  /** 운영 Gemini 설정(gemini·gemini-embedding-001·768)이 엔진에서 만드는 세대(증거 7: V18 뒤 재임베딩 0). */
  static final EmbeddingGeneration GEMINI_SETTINGS =
      EmbeddingGeneration.of(
          new EmbedderIdentity("gemini", "gemini-embedding-001", 768),
          EmbeddingGenerationCheck.INPUT_FORMAT);

  /** V17까지 적용한 새 데이터베이스. */
  static MigrationRehearsal atV17(String database) {
    return MigrationRehearsal.freshDatabase(database).migrateTo("17");
  }

  static void givenSpaceMeta(JdbcClient jdbc, String space, String key, String value) {
    jdbc.sql("INSERT INTO space_meta (space_id, key, value) VALUES (?, ?, ?)")
        .params(space, key, value)
        .update();
  }

  static void givenCached(JdbcClient jdbc, String generation, String inputSha256) {
    jdbc.sql(
            "INSERT INTO embedding_cache (generation, input_sha256, embedding) VALUES (?, ?, ?::vector)")
        .params(generation, inputSha256, VECTOR)
        .update();
  }

  /** 두 표의 행 전부(값까지) — 바뀌지 않았는지 대조한다. */
  static List<String> labelRows(JdbcClient jdbc) {
    List<String> meta =
        jdbc.sql("SELECT space_id || '|' || key || '|' || value FROM space_meta ORDER BY 1")
            .query(String.class)
            .list();
    List<String> cache =
        jdbc.sql(
                "SELECT generation || '|' || input_sha256 || '|' || embedding::text FROM embedding_cache ORDER BY 1")
            .query(String.class)
            .list();
    return Stream.concat(meta.stream(), cache.stream()).toList();
  }

  @Nested
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  @DisplayName("옛 Gemini 라벨이 있는 V17 DB에 V18을 적용하면")
  class LegacyLabels {
    JdbcClient jdbc;

    @BeforeAll
    void migrateOverLegacyLabels() {
      MigrationRehearsal rehearsal = atV17("scos_v18_legacy");
      jdbc = rehearsal.jdbc();
      givenSpaceMeta(jdbc, "legacy", "embedding:version", LEGACY);
      givenSpaceMeta(jdbc, "legacy", "cursor:slack", "2026-10-13T00:00:00.000Z");
      givenCached(jdbc, LEGACY, "a1");
      jdbc.sql(
              "INSERT INTO unit_vectors (space_id, unit_id, document_id, embedding) VALUES ('legacy', 'u1', 'd1', ?::vector)")
          .param(VECTOR)
          .update();
      rehearsal.migrateToLatest();
    }

    @Test
    @DisplayName("space_meta의 옛 Gemini 라벨을 새 형식으로 바꾼다")
    void rewritesSpaceMetaLabel() {
      // then
      assertThat(
              jdbc.sql(
                      "SELECT value FROM space_meta WHERE space_id = 'legacy' AND key = 'embedding:version'")
                  .query(String.class)
                  .single())
          .isEqualTo(GEMINI);
    }

    @Test
    @DisplayName("embedding_cache의 옛 Gemini 라벨을 새 형식으로 바꾼다")
    void rewritesCacheLabel() {
      // then
      assertThat(jdbc.sql("SELECT generation FROM embedding_cache").query(String.class).list())
          .containsExactly(GEMINI);
    }

    @Test
    @DisplayName("벡터와 캐시 벡터 값은 바꾸지 않는다")
    void keepsVectors() {
      // then
      assertThat(
              jdbc.sql(
                      "SELECT embedding::text FROM unit_vectors UNION ALL SELECT embedding::text FROM embedding_cache")
                  .query(String.class)
                  .list())
          .containsExactly(VECTOR, VECTOR);
    }

    @Test
    @DisplayName("V18을 거친 옛 Gemini 공간은 Gemini 설정의 세대 검사를 통과한다")
    void passesGeminiGenerationCheck() {
      // when·then
      assertThatCode(
              () ->
                  EmbeddingGenerationCheck.verify(
                      new PostgresSpaceMeta(jdbc), List.of(new SpaceId("legacy")), GEMINI_SETTINGS))
          .doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("V17 DB에 모르는 옛 라벨이 있을 때")
  class UnknownLabel {
    @ParameterizedTest(name = "[{0}]")
    @CsvSource(
        delimiter = '|',
        value = {
          "space_meta|INSERT INTO space_meta (space_id, key, value) VALUES ('legacy', 'embedding:version', 'GeminiEmbedder:3072:title+aliases+content')",
          "embedding_cache|INSERT INTO embedding_cache (generation, input_sha256, embedding) VALUES ('GeminiEmbedder:3072:title+aliases+content', 'a1', '[1,0]')"
        })
    @DisplayName("모르는 옛 라벨이 있으면 마이그레이션이 실패한다")
    void failsOnUnknownLabel(String table, String insert) {
      // given
      MigrationRehearsal rehearsal = atV17("scos_v18_unknown_" + table);
      rehearsal.jdbc().sql(insert).update();

      // when·then
      assertThatThrownBy(rehearsal::migrateToLatest)
          .isInstanceOf(FlywayException.class)
          .hasMessageContaining("unknown embedding generation label");
    }
  }

  @Nested
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  @DisplayName("옛 라벨이 없는 V17 DB에 V18을 적용하면")
  class NoLegacyLabels {
    JdbcClient jdbc;
    List<String> before;

    @BeforeAll
    void migrateWithoutLegacyLabels() {
      MigrationRehearsal rehearsal = atV17("scos_v18_current");
      jdbc = rehearsal.jdbc();
      givenSpaceMeta(
          jdbc,
          "public",
          "embedding:version",
          "openai-compatible:nomic-embed-text:v1.5:768:title+aliases+content");
      givenSpaceMeta(jdbc, "public", "cursor:slack", "2026-10-13T00:00:00.000Z");
      givenCached(jdbc, GEMINI, "a1");
      before = labelRows(jdbc);
      rehearsal.migrateToLatest();
    }

    @Test
    @DisplayName("옛 라벨이 없는 DB에서는 아무 행도 바꾸지 않는다")
    void changesNothing() {
      // then
      assertThat(labelRows(jdbc)).isEqualTo(before);
    }
  }
}
