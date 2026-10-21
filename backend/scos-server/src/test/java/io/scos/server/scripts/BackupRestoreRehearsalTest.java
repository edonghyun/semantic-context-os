package io.scos.server.scripts;

import static io.scos.spi.store.ContextUnits.unit;
import static org.assertj.core.api.Assertions.assertThat;

import io.scos.collection.internal.postgres.PostgresSupport;
import io.scos.engine.internal.projection.UnitImporter;
import io.scos.engine.internal.search.QuestionSearch;
import io.scos.engine.internal.search.Reranker;
import io.scos.engine.internal.search.SearchSettings;
import io.scos.spi.embedding.Embedder;
import io.scos.spi.embedding.FixedEmbedder;
import io.scos.spi.store.AccessScope;
import io.scos.spi.store.SpaceId;
import io.scos.store.postgres.PostgresTestAdapters;
import io.scos.support.TestExecutors;
import io.scos.support.TestMetrics;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 백업·복원 리허설(T10.1): 운영과 같은 이미지의 컨테이너에 스키마(Flyway)와 유닛을 넣고, 실제 백업 스크립트로 덤프한 뒤, 빈 새 컨테이너에 실제 복원 스크립트로
 * 되살린다. 복원본에서 같은 질문의 검색 결과가 원본과 같아야 한다 — 키워드·벡터 색인과 마이그레이션 이력까지 덤프에 들어 있다는 뜻이다. 스크립트는 `docker
 * exec`로 컨테이너 안의 pg_dump·pg_restore를 쓴다(호스트 pg_dump 버전과 무관). 이어서 그 DB에 덤프 뒤 테이블을 더한 다음 같은 덤프로 다시
 * 복원하면 그 테이블은 없어야 한다 — 운영의 되돌리기는 빈 DB가 아니라 채워진 DB 위에서 한다(리뷰 A I1). 컨테이너·스크립트 실행이 비싸서
 * 상황마다 @BeforeAll에서 한 번 실행하고 기록을 읽는다(TESTING §2.5 규칙 6).
 */
@Tag("db")
@Tag("process")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("백업 덤프를 실제 복원하면 검색 결과와 스키마 이력이 그대로다(T10.1)")
class BackupRestoreRehearsalTest {
  static final SpaceId SPACE = new SpaceId("legacy");
  static final String QUESTION = "정산 배치 재시도";
  static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-16T00:00:00Z"), ZoneOffset.UTC);

  /** 질문·변형 모두 같은 벡터(유닛 임베딩과 같은 차원 2) — 빈 키워드는 모든 텍스트에 맞는다. */
  static final Embedder FIXED =
      FixedEmbedder.byKeyword("", new double[] {1, 0}, new double[] {1, 0});

  final PostgreSQLContainer source = new PostgreSQLContainer(PostgresSupport.IMAGE);
  final PostgreSQLContainer target = new PostgreSQLContainer(PostgresSupport.IMAGE);
  final ScriptHarness scripts = new ScriptHarness().withEnvFile();

  void useRealDocker() {
    scripts.env.put("PATH", "/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin");
    scripts.env.put("HOME", System.getProperty("user.home"));
    String dockerHost = System.getenv("DOCKER_HOST");
    if (dockerHost != null) scripts.env.put("DOCKER_HOST", dockerHost);
  }

  ScriptHarness scriptFor(PostgreSQLContainer container) {
    scripts.env.put("SCOS_PG_CONTAINER", container.getContainerId());
    scripts.env.put("SCOS_PG_USER", container.getUsername());
    scripts.env.put("SCOS_PG_DB", container.getDatabaseName());
    return scripts;
  }

  Path onlyDump() {
    try (Stream<Path> files = Files.list(scripts.home.resolve("backups"))) {
      return files.filter(p -> p.toString().endsWith(".dump")).findFirst().orElseThrow();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static DataSource dataSource(PostgreSQLContainer c) {
    return new DriverManagerDataSource(c.getJdbcUrl(), c.getUsername(), c.getPassword());
  }

  static List<String> hits(DataSource ds) {
    return new QuestionSearch(
            PostgresTestAdapters.unitStore(ds),
            PostgresTestAdapters.keywordIndex(ds),
            PostgresTestAdapters.vectorIndex(ds),
            FIXED,
            Reranker.DISABLED,
            SearchSettings.DEFAULT,
            CLOCK,
            TestMetrics.search(),
            TestExecutors.CALLER_THREAD)
        .search(AccessScope.singleUser(), SPACE, QUESTION).candidates().stream()
            .map(c -> c.unit().id() + "@" + c.baseScore())
            .toList();
  }

  static long migrations(DataSource ds) {
    return JdbcClient.create(ds)
        .sql("SELECT count(*) FROM flyway_schema_history WHERE success")
        .query(Long.class)
        .single();
  }

  @Nested
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  @DisplayName("원본을 백업해 덤프를 빈 새 컨테이너에 복원하면")
  class RestoredIntoEmpty {
    List<String> sourceHits;
    List<String> restoredHits;
    long sourceMigrations;
    long restoredMigrations;
    int backupExit;
    int restoreExit;

    @BeforeAll
    void rehearse() {
      source.start();
      target.start();
      DataSource from = dataSource(source);
      Flyway.configure().dataSource(from).locations("classpath:db/migration").load().migrate();
      new UnitImporter(
              new TransactionTemplate(new DataSourceTransactionManager(from)),
              PostgresTestAdapters.unitStore(from),
              PostgresTestAdapters.keywordIndex(from),
              PostgresTestAdapters.vectorIndex(from))
          .importUnits(
              SPACE,
              List.of(
                  unit("u1").inDocument("d1").content("정산 배치는 매일 자정에 실행된다").embedding(1, 0).build(),
                  unit("u2")
                      .inDocument("d2")
                      .content("정산 배치 재시도 규칙은 세 번이다")
                      .embedding(0.6, 0.8)
                      .build(),
                  unit("u3").inDocument("d3").content("휴가 신청 절차").embedding(0, 1).build()));
      sourceHits = hits(from);
      if (sourceHits.isEmpty()) throw new IllegalStateException("given 준비 실패: 원본에서 검색 결과가 없다");
      sourceMigrations = migrations(from);

      useRealDocker();
      backupExit = scriptFor(source).run("scos-backup.sh").exitCode();
      restoreExit =
          scriptFor(target).run("scos-restore.sh", onlyDump().toString(), "--yes").exitCode();
      DataSource to = dataSource(target);
      restoredHits = hits(to);
      restoredMigrations = migrations(to);
    }

    @AfterAll
    void close() {
      scripts.close();
      source.stop();
      target.stop();
    }

    @Test
    @DisplayName("백업 스크립트가 성공한다")
    void backupSucceeds() {
      // then
      assertThat(backupExit).isZero();
    }

    @Test
    @DisplayName("복원 스크립트가 성공한다")
    void restoreSucceeds() {
      // then
      assertThat(restoreExit).isZero();
    }

    @Test
    @DisplayName("같은 질문의 검색 결과(유닛·점수·순서)가 원본과 같다")
    void sameSearchResults() {
      // then
      assertThat(restoredHits).isEqualTo(sourceHits);
    }

    @Test
    @DisplayName("마이그레이션 이력도 그대로 옮겨진다")
    void sameMigrationHistory() {
      // then
      assertThat(restoredMigrations).isEqualTo(sourceMigrations);
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @DisplayName("같은 덤프를 그 컨테이너에 없는 DB 이름(새 호스트)으로 복원하면")
    class RestoredWhereTargetMissing {
      static final String NEW_HOST_DB = "scos_new_host";
      int restoreExit;
      List<String> newHostHits;

      @BeforeAll
      void restoreUnderNewName() {
        // 재해 복구의 실제 모양: 새 호스트에는 대상 DB가 아직 없다 — 바꿀 원래 DB 없이 새 DB가 대상 이름이 된다
        scriptFor(target).env.put("SCOS_PG_DB", NEW_HOST_DB);
        restoreExit = scripts.run("scos-restore.sh", onlyDump().toString(), "--yes").exitCode();
        newHostHits =
            restoreExit == 0
                ? hits(
                    new DriverManagerDataSource(
                        "jdbc:postgresql://"
                            + target.getHost()
                            + ":"
                            + target.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
                            + "/"
                            + NEW_HOST_DB,
                        target.getUsername(),
                        target.getPassword()))
                : List.of();
      }

      @Test
      @DisplayName("복원 스크립트가 성공한다")
      void restoreSucceeds() {
        // then
        assertThat(restoreExit).isZero();
      }

      @Test
      @DisplayName("대상 이름의 DB에서 검색 결과가 원본과 같다")
      void sameSearchResults() {
        // then
        assertThat(newHostHits).isEqualTo(sourceHits);
      }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @DisplayName("그 DB에 다른 연결이 열린 채 같은 덤프로 복원하면")
    class RefusedWhileConnected {
      ScriptHarness.Result refused;
      List<String> restoreDatabases;
      boolean markerSurvived;
      Connection other;

      @BeforeAll
      void restoreWhileConnected() throws SQLException {
        // 운영의 실제 모양: 데몬·MCP가 대상 DB에 연결해 있다. 표시 테이블로 대상 DB가 그대로인지 본다
        JdbcClient.create(dataSource(target)).sql("CREATE TABLE refused_marker (id int)").update();
        other = dataSource(target).getConnection();
        refused = scriptFor(target).run("scos-restore.sh", onlyDump().toString(), "--yes");
        restoreDatabases =
            JdbcClient.create(dataSource(target))
                .sql("SELECT datname FROM pg_database WHERE datname LIKE :prefix")
                .param("prefix", target.getDatabaseName() + "\\_\\_restore\\_%")
                .query(String.class)
                .list();
        markerSurvived =
            JdbcClient.create(dataSource(target))
                    .sql(
                        "SELECT count(*) FROM information_schema.tables WHERE table_name = 'refused_marker'")
                    .query(Long.class)
                    .single()
                > 0;
      }

      @AfterAll
      void disconnect() throws SQLException {
        other.close();
        JdbcClient.create(dataSource(target)).sql("DROP TABLE IF EXISTS refused_marker").update();
      }

      @Test
      @DisplayName("종료 코드 1로 끝난다")
      void exitsOne() {
        // then
        assertThat(refused.exitCode()).isEqualTo(1);
      }

      @Test
      @DisplayName("복원 전에 다른 연결 수 1을 남기고, 연결을 이유로 거부한다")
      void refusesBeforeRestoring() {
        // then
        assertThat(refused.stderr())
            .contains("\"event\":\"restore.target\"")
            .contains("\"otherConnections\":1,")
            .contains("target database has other connections — stop the daemon and MCP first");
      }

      @Test
      @DisplayName("복원용 새 DB를 만들지 않는다")
      void createsNoRestoreDatabase() {
        // then
        assertThat(restoreDatabases).isEmpty();
      }

      @Test
      @DisplayName("대상 DB를 바꾸지 않는다")
      void keepsTargetDatabase() {
        // then
        assertThat(markerSurvived).isTrue();
      }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @DisplayName("그 DB에 덤프 뒤 테이블을 더하고 같은 덤프로 다시 복원하면")
    class RestoredOverPopulated {
      int restoreOverExit;
      boolean addedTableSurvived;
      List<String> restoredAgainHits;

      @BeforeAll
      void restoreAgain() {
        // 운영의 실제 모양: 이미 채워진 DB가 덤프 뒤에 바뀌었다(예: 새 마이그레이션의 테이블) — 같은 덤프로 다시 되돌린다
        JdbcClient.create(dataSource(target))
            .sql("CREATE TABLE added_after_dump (id int)")
            .update();
        restoreOverExit =
            scriptFor(target).run("scos-restore.sh", onlyDump().toString(), "--yes").exitCode();
        addedTableSurvived =
            JdbcClient.create(dataSource(target))
                    .sql(
                        "SELECT count(*) FROM information_schema.tables WHERE table_name = 'added_after_dump'")
                    .query(Long.class)
                    .single()
                > 0;
        restoredAgainHits = hits(dataSource(target));
      }

      @Test
      @DisplayName("이미 채워진 DB 위의 복원도 성공한다")
      void restoreOverExistingSucceeds() {
        // then
        assertThat(restoreOverExit).isZero();
      }

      @Test
      @DisplayName("덤프 뒤에 만든 테이블은 사라진다")
      void objectsCreatedAfterDumpAreGone() {
        // then
        assertThat(addedTableSurvived).isFalse();
      }

      @Test
      @DisplayName("다시 복원한 DB도 검색 결과가 원본과 같다")
      void restoredAgainSameSearch() {
        // then
        assertThat(restoredAgainHits).isEqualTo(sourceHits);
      }
    }
  }
}
