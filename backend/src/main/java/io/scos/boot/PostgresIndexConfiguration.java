package io.scos.boot;

import io.scos.spi.store.EmbeddingCache;
import io.scos.spi.store.KeywordIndex;
import io.scos.spi.store.SpaceMeta;
import io.scos.spi.store.UnitStore;
import io.scos.spi.store.VectorIndex;
import io.scos.store.postgres.PostgresEmbeddingCache;
import io.scos.store.postgres.PostgresKeywordIndex;
import io.scos.store.postgres.PostgresSpaceMeta;
import io.scos.store.postgres.PostgresUnitStore;
import io.scos.store.postgres.PostgresVectorIndex;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 검색 모듈의 PostgreSQL 어댑터 조립. 수집 모듈과 같은 DataSource를 쓰면 투영 트랜잭션 안에서 색인이 갱신된다(TRANSACTIONAL). */
@Configuration(proxyBeanMethods = false)
public class PostgresIndexConfiguration {
  @Bean
  VectorIndex vectorIndex(DataSource dataSource) {
    return new PostgresVectorIndex(new NamedParameterJdbcTemplate(dataSource));
  }

  @Bean
  KeywordIndex keywordIndex(DataSource dataSource) {
    return new PostgresKeywordIndex(JdbcClient.create(dataSource));
  }

  @Bean
  UnitStore unitStore(DataSource dataSource) {
    return new PostgresUnitStore(new NamedParameterJdbcTemplate(dataSource));
  }

  @Bean
  SpaceMeta spaceMeta(DataSource dataSource) {
    return new PostgresSpaceMeta(JdbcClient.create(dataSource));
  }

  @Bean
  EmbeddingCache embeddingCache(DataSource dataSource) {
    return embeddingCache(new NamedParameterJdbcTemplate(dataSource));
  }

  /**
   * 일회성 이전 도구(F9 `migration` 서브프로젝트, ADR-0026)가 Spring 컨텍스트 없이 같은 저장 경로로 캐시를 채울 때 쓴다 — 스키마·SQL을
   * 복제하지 않는다.
   */
  public static EmbeddingCache embeddingCache(NamedParameterJdbcTemplate jdbc) {
    return new PostgresEmbeddingCache(jdbc);
  }
}
