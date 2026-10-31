package io.scos.engine;

import io.scos.collection.internal.postgres.PostgresSupport;
import io.scos.store.postgres.PostgresAccessSeed;
import io.scos.store.postgres.PostgresAclStore;
import io.scos.store.postgres.PostgresIdentityStore;
import io.scos.store.postgres.PostgresTestAdapters;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 원본과 오라클을 같은 컨테이너의 독립 스키마에 적재한다. 각 사용 전에 비운다. */
// DB에 의존하는 보조 클래스도 태그를 요구하는 TestCodeRulesTest의 구조 검증용 메타데이터다.
@Tag("db")
final class PostgresAccessAdapters {
  // 단일 suite의 포크 내 직렬 실행 전용이다. 다른 사용처와 공유하면 서로 TRUNCATE한다.
  private static final List<DataSource> SCHEMAS = new ArrayList<>();
  private int nextSchema;

  AccessAdapters create() {
    DataSource source = source(nextSchema++);
    var jdbc = JdbcClient.create(source);
    String tables =
        jdbc.sql(
                """
        SELECT string_agg(format('%I.%I', schemaname, tablename), ', ')
        FROM pg_tables WHERE schemaname = current_schema() AND tablename <> 'flyway_schema_history'
        """)
            .query(String.class)
            .single();
    jdbc.sql("TRUNCATE " + tables + " RESTART IDENTITY CASCADE").update();
    return new AccessAdapters(
        new PostgresAclStore(jdbc),
        PostgresTestAdapters.unitStore(source),
        PostgresTestAdapters.keywordIndex(source),
        PostgresTestAdapters.vectorIndex(source),
        PostgresTestAdapters.unitMaintenance(source),
        new PostgresIdentityStore(jdbc),
        new PostgresAccessSeed(source)::seed);
  }

  private DataSource source(int index) {
    if (index == SCHEMAS.size()) SCHEMAS.add(migratedSchema(index));
    return SCHEMAS.get(index);
  }

  private DataSource migratedSchema(int index) {
    // URL을 읽기 전에 공유 Testcontainers 컨테이너를 기동한다.
    PostgresSupport.dataSource();
    String schema = "access_scenario_" + index;
    var source =
        new DriverManagerDataSource(
            PostgresSupport.jdbcUrl(), PostgresSupport.username(), PostgresSupport.password());
    var properties = new Properties();
    properties.setProperty("currentSchema", schema + ",public");
    properties.setProperty(
        "options",
        "-c lock_timeout="
            + PostgresSupport.LOCK_TIMEOUT
            + " -c statement_timeout="
            + PostgresSupport.STATEMENT_TIMEOUT);
    source.setConnectionProperties(properties);
    Flyway.configure()
        .dataSource(source)
        .schemas(schema)
        .defaultSchema(schema)
        .locations("classpath:db/migration")
        .load()
        .migrate();
    return source;
  }
}
