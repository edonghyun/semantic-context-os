package io.scos.store.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.boot.PostgresIndexConfiguration;
import io.scos.collection.internal.postgres.PostgresSupport;
import io.scos.spi.store.EmbeddingCache;
import io.scos.spi.store.EmbeddingCacheContract;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** 임베딩 캐시 계약 — PostgreSQL(V16 `embedding_cache`). */
@Tag("db")
@DisplayName("PostgreSQL 임베딩 캐시는 계약을 지키고 비우면 디스크를 돌려준다(ADR-0026)")
class PostgresEmbeddingCacheTest extends EmbeddingCacheContract {
  @BeforeEach
  void clean() {
    PostgresSupport.truncateAll();
  }

  @Override
  protected EmbeddingCache newCache() {
    return PostgresIndexConfiguration.embeddingCache(
        new NamedParameterJdbcTemplate(PostgresSupport.dataSource()));
  }

  @Test
  @DisplayName("비우면 표가 차지한 디스크 공간을 바로 돌려준다")
  void clearReleasesTableSpace() {
    // given
    cache.putIfAbsent(GENERATION, Map.of(KEY_A, VECTOR_A, KEY_B, VECTOR_B));

    // when
    cache.clear();

    // then
    assertThat(
            new JdbcTemplate(PostgresSupport.dataSource())
                .queryForObject("SELECT pg_relation_size('embedding_cache')", Long.class))
        .as("표 본체(heap) 크기")
        .isZero();
  }
}
