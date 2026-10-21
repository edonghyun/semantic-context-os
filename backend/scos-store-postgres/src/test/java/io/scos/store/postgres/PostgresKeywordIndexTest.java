package io.scos.store.postgres;

import io.scos.collection.internal.postgres.PostgresSupport;
import io.scos.spi.store.KeywordIndex;
import io.scos.spi.store.KeywordIndexContract;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * P-4 첫 어댑터(직접 bigram 테이블)가 키워드 색인 계약을 지키는지. 품질·지연 비교는 옛 experiments/pa/pg-lab
 * P4KeywordCandidatesTest(f93f5966 이전 이력).
 */
@DisplayName("키워드 색인 계약 — PostgreSQL bigram 어댑터 판(CONTRACTS B3)")
@Tag("db")
class PostgresKeywordIndexTest extends KeywordIndexContract {
  @BeforeEach
  void clean() {
    PostgresSupport.truncateAll();
  }

  @Override
  protected KeywordIndex newIndex() {
    return new PostgresKeywordIndex(JdbcClient.create(PostgresSupport.dataSource()));
  }
}
