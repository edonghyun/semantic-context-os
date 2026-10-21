package io.scos.collection.internal.postgres;

import java.util.List;
import java.util.Properties;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * db 태그 테스트 공용 컨테이너(ADR-0015). 이미지 태그는 한 곳에 고정한다. Flyway로 제품 마이그레이션을 그대로 적용하고, 테스트 사이에는 TRUNCATE로
 * 격리한다. Docker가 없으면 기동 실패 = 테스트 실패(skip 아님).
 */
public final class PostgresSupport {
  /**
   * pg17 태그 + 인덱스 digest(2026-10-15 확인: PostgreSQL 17.11, pgvector 0.8.7). 하네스 PostgresImage와 같은 값.
   */
  public static final DockerImageName IMAGE =
      DockerImageName.parse(
              "pgvector/pgvector:pg17@sha256:ac08538c6f8b9904c33c8224c5e5706dbe760aca29db1d096972b4052c22a75d")
          .asCompatibleSubstituteFor("postgres");

  public static final String LOCK_TIMEOUT = "20s";
  public static final String STATEMENT_TIMEOUT = "120s";
  private static PostgreSQLContainer container;
  private static DataSource dataSource;

  private PostgresSupport() {}

  public static synchronized DataSource dataSource() {
    if (dataSource == null) {
      container = new PostgreSQLContainer(IMAGE);
      container.start();
      DriverManagerDataSource ds =
          new DriverManagerDataSource(
              container.getJdbcUrl(), container.getUsername(), container.getPassword());
      // 누수된 트랜잭션이 다음 테스트(TRUNCATE)를 무기한 막지 못하게 — 서버 측 상한(TESTING §7: 상한은 hang 방지용)
      Properties props = new Properties();
      props.setProperty(
          "options",
          "-c lock_timeout=" + LOCK_TIMEOUT + " -c statement_timeout=" + STATEMENT_TIMEOUT);
      ds.setConnectionProperties(props);
      Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
      dataSource = ds;
    }
    return dataSource;
  }

  public static String jdbcUrl() {
    dataSource();
    return container.getJdbcUrl();
  }

  public static String username() {
    dataSource();
    return container.getUsername();
  }

  public static String password() {
    dataSource();
    return container.getPassword();
  }

  /**
   * 테이블 목록을 손으로 적지 않는다(TESTING §6.1): Flyway 이력을 뺀 public 테이블 전부, RESTART IDENTITY CASCADE. 그 뒤
   * 마이그레이션이 넣는 시드 행({@link #MIGRATION_SEEDS})을 되돌린다 — 테스트가 운영(마이그레이션 직후)과 같은 상태에서 시작한다.
   */
  public static void truncateAll() {
    JdbcClient jdbc = JdbcClient.create(dataSource());
    String tables =
        jdbc.sql(
                """
                SELECT string_agg(format('%I', tablename), ', ')
                FROM pg_tables WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'
                """)
            .query(String.class)
            .single();
    jdbc.sql("TRUNCATE " + tables + " RESTART IDENTITY CASCADE").update();
    for (String seed : MIGRATION_SEEDS) jdbc.sql(seed).update();
  }

  /** 마이그레이션의 데이터 시드(INSERT). 마이그레이션에 시드를 더하면 여기에도 더한다 — MigrationSeedsTest가 대조한다. */
  static final List<String> MIGRATION_SEEDS =
      List.of("INSERT INTO raw_change_retention (id, floor) VALUES (1, 0)");
}
