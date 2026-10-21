package io.scos.store.postgres;

import io.scos.spi.store.KeywordIndex;
import io.scos.spi.store.SpaceMeta;
import io.scos.spi.store.UnitStore;
import io.scos.spi.store.VectorIndex;
import javax.sql.DataSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 다른 패키지의 테스트(projection 등)가 package-private 어댑터를 쓰기 위한 테스트 지원(제품 코드의 static 팩터리 대신). */
public final class PostgresTestAdapters {
  private PostgresTestAdapters() {}

  public static UnitStore unitStore(DataSource ds) {
    return new PostgresUnitStore(new NamedParameterJdbcTemplate(ds));
  }

  public static KeywordIndex keywordIndex(DataSource ds) {
    return new PostgresKeywordIndex(JdbcClient.create(ds));
  }

  public static VectorIndex vectorIndex(DataSource ds) {
    return new PostgresVectorIndex(new NamedParameterJdbcTemplate(ds));
  }

  public static SpaceMeta spaceMeta(DataSource ds) {
    return new PostgresSpaceMeta(JdbcClient.create(ds));
  }
}
