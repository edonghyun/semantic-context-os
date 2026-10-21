package io.scos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;

import io.scos.collection.internal.postgres.PostgresSupport;
import io.scos.server.ScosApplication;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * CONTRACTS A6(보존)·ADR-0005: `mcp` 실행은 스키마를 바꾸지 않고(Flyway migrate 없음, validate만) 읽기 전용 연결을 쓴다. 실제
 * Boot 컨텍스트를 mcp+harness 프로필로 띄운다(stdio 서버는 테스트 stdin EOF로 바로 유휴).
 */
@Tag("db")
@DisplayName("mcp 실행은 스키마를 바꾸지 않고 읽기 전용으로 연결한다(A6·ADR-0005)")
class McpReadOnlyTest {
  /**
   * application.yml의 `${SCOS_DB_URL:}` 자리표시자보다 우선하려면 명령행 인자(최우선)로 넘겨야 한다(builder.properties는 기본값
   * 층이라 밀린다).
   */
  static String[] args(String jdbcUrl) {
    String cassette =
        Path.of(System.getProperty("scos.fixtures.dir", "../harness/fixtures/p1"), "cassette.json")
            .toAbsolutePath()
            .toString();
    return new String[] {
      "mcp",
      "--spring.datasource.url=" + jdbcUrl,
      "--spring.datasource.username=" + PostgresSupport.username(),
      "--spring.datasource.password=" + PostgresSupport.password(),
      "--scos.harness.now=2026-10-13T00:00:00Z",
      "--scos.harness.cassette=" + cassette,
      "--scos.mcp.space-id=legacy"
    };
  }

  /** 공용 컨테이너의 DB에 제품 마이그레이션이 적용된 상태를 만든다(지연 싱글턴의 부수효과를 이름으로 드러낸다). */
  static String givenMigratedDatabaseUrl() {
    PostgresSupport.dataSource();
    return PostgresSupport.jdbcUrl();
  }

  static ConfigurableApplicationContext startMcp(String url) {
    return new SpringApplicationBuilder(ScosApplication.class)
        .web(WebApplicationType.NONE)
        .profiles("mcp", "harness")
        .run(args(url));
  }

  @Nested
  @TestInstance(PER_CLASS)
  @DisplayName("마이그레이션된 DB에 mcp 프로필로 띄우면")
  class OnMigratedDatabase {
    boolean readOnly;
    Throwable insertFailure;
    boolean readWorks;

    @BeforeAll
    void startAndProbe() throws Exception {
      try (ConfigurableApplicationContext ctx = startMcp(givenMigratedDatabaseUrl());
          Connection c = ctx.getBean(DataSource.class).getConnection();
          Statement st = c.createStatement()) {
        readOnly = c.isReadOnly();
        try {
          st.execute(
              "INSERT INTO document_spaces (document_id, space_id) VALUES ('ro-probe', 'legacy')");
        } catch (SQLException e) {
          insertFailure = e;
        }
        readWorks = st.executeQuery("SELECT count(*) FROM units").next();
      }
    }

    @Test
    @DisplayName("연결은 읽기 전용이다")
    void connectionIsReadOnly() {
      // then
      assertThat(readOnly).isTrue();
    }

    @Test
    @DisplayName("INSERT는 읽기 전용 트랜잭션으로 거부된다")
    void rejectsWrites() {
      // then
      assertThat(insertFailure).hasMessageContaining("read-only");
    }

    @Test
    @DisplayName("읽기는 된다")
    void allowsReads() {
      // then
      assertThat(readWorks).isTrue();
    }
  }

  @Nested
  @TestInstance(PER_CLASS)
  @DisplayName("마이그레이션되지 않은 빈 DB에 mcp 프로필로 띄우면")
  class OnEmptyDatabase {
    Throwable startupFailure;
    int tables;

    @BeforeAll
    void startOnEmptyDatabase() throws Exception {
      try (Connection c = PostgresSupport.dataSource().getConnection();
          Statement st = c.createStatement()) {
        st.execute("DROP DATABASE IF EXISTS scos_empty");
        st.execute("CREATE DATABASE scos_empty");
      }
      String emptyUrl = PostgresSupport.jdbcUrl().replaceAll("/[^/?]+(\\?|$)", "/scos_empty$1");
      try (ConfigurableApplicationContext ctx = startMcp(emptyUrl)) {
        startupFailure = null;
      } catch (RuntimeException e) {
        startupFailure = e;
      }
      try (Connection c =
              DriverManager.getConnection(
                  emptyUrl, PostgresSupport.username(), PostgresSupport.password());
          Statement st = c.createStatement()) {
        var rs =
            st.executeQuery(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public'");
        rs.next();
        tables = rs.getInt(1);
      }
    }

    @Test
    @DisplayName("기동은 스키마 검증(validate) 실패로 끝난다")
    void failsValidation() {
      // then
      assertThat(startupFailure).hasRootCauseInstanceOf(FlywayValidateException.class);
    }

    @Test
    @DisplayName("스키마를 만들지 않는다(migrate 없음)")
    void createsNoTables() {
      // then
      assertThat(tables).isZero();
    }
  }
}
