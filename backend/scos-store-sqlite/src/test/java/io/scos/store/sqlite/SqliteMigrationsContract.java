package io.scos.store.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.nio.file.Path;
import java.sql.SQLException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("공개 SQLite 마이그레이션은 버전 순서로 원자적으로 적용한다(B4)")
abstract class SqliteMigrationsContract {
  abstract MigrationScenario scenario(Path dir);

  @Nested
  @DisplayName("빈 파일인 상태에서")
  class EmptyFile {
    private MigrationScenario scenario;

    @BeforeEach
    void setUp(@TempDir Path dir) {
      scenario = scenario(dir);
    }

    @Test
    @DisplayName("스키마를 만들면 생성으로 보고한다")
    void reportsCreatedSchema() throws SQLException {
      // when
      boolean created = scenario.apply("db/migrations/ordered");

      // then
      assertThat(created).isTrue();
    }

    @Test
    @DisplayName("버전 번호가 여러 자리여도 번호 순서대로 적용한다")
    void appliesScriptsInOrder() throws SQLException {
      // when
      scenario.apply("db/migrations/ordered");

      // then
      assertThat(scenario.value("SELECT value FROM example")).isEqualTo("tenth");
    }

    @Test
    @DisplayName("모든 스크립트를 적용하면 마지막 버전을 기록한다")
    void recordsLatestVersion() throws SQLException {
      // when
      scenario.apply("db/migrations/ordered");

      // then
      assertThat(scenario.value("PRAGMA user_version")).isEqualTo("10");
    }

    @Test
    @DisplayName("중간 스크립트가 실패하면 스키마 버전도 되돌린다")
    void rollsBackVersion() throws SQLException {
      // when
      Throwable failure = catchThrowable(() -> scenario.apply("db/migrations/broken"));

      // then
      assertThat(failure)
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("no such table: missing");
      assertThat(scenario.value("PRAGMA user_version")).isEqualTo("0");
      assertThat(scenario.value("SELECT count(*) FROM sqlite_master WHERE name = 'example'"))
          .isEqualTo("0");
    }
  }

  @Nested
  @DisplayName("이전 버전의 스키마가 적용된 파일인 상태에서")
  class PreviousVersion {
    private MigrationScenario scenario;

    @BeforeEach
    void setUp(@TempDir Path dir) throws SQLException {
      scenario = scenario(dir);
      scenario.givenVersionOne();
    }

    @Test
    @DisplayName("스키마를 올리면 새 파일 생성으로 보고하지 않는다")
    void upgradeIsNotCreation() throws SQLException {
      // when
      boolean created = scenario.apply("db/migrations/ordered");

      // then
      assertThat(created).isFalse();
    }
  }

  @Nested
  @DisplayName("최신 스키마가 적용된 파일인 상태에서")
  class LatestFile {
    private MigrationScenario scenario;

    @BeforeEach
    void setUp(@TempDir Path dir) throws SQLException {
      scenario = scenario(dir);
      scenario.apply("db/migrations/ordered");
    }

    @Test
    @DisplayName("다시 적용하면 생성으로 보고하지 않는다")
    void latestIsUnchanged() throws SQLException {
      // when
      boolean created = scenario.apply("db/migrations/ordered");

      // then
      assertThat(created).isFalse();
    }
  }
}
