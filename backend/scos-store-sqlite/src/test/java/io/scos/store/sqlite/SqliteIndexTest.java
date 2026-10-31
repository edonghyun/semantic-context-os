package io.scos.store.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.spi.store.SpaceId;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("SqliteIndex는 한 파일을 열고 스키마를 한 번만 만든다")
class SqliteIndexTest {
  private static final SpaceId SPACE = new SpaceId("s");
  private static final Duration OPEN_TIMEOUT = Duration.ofSeconds(10);

  /** 잠금을 기다리지 않은 열기의 상한(busy_timeout 5초보다 충분히 짧다). */
  private static final Duration PROMPT_OPEN = Duration.ofSeconds(2);

  @Test
  @DisplayName("드라이버의 SQLite는 FTS5 trigram을 만든다")
  void fts5TrigramAvailable(@TempDir Path dir) throws Exception {
    // given
    try (var c = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("probe.sqlite"));
        var st = c.createStatement()) {
      // when
      st.execute("CREATE VIRTUAL TABLE t USING fts5(x, tokenize='trigram')");
      st.execute("INSERT INTO t (x) VALUES ('정산 보고서')");

      // then
      try (var rs = st.executeQuery("SELECT count(*) FROM t WHERE t MATCH '\"정산 \"'")) {
        assertThat(rs.getInt(1)).isEqualTo(1);
      }
      try (var rs = st.executeQuery("SELECT sqlite_version()")) {
        assertThat(rs.getString(1)).isGreaterThanOrEqualTo("3.34");
      }
    }
  }

  @Test
  @DisplayName("빈 파일에 저장한 메타데이터는 다시 열어도 남아 있다")
  void metadataSurvivesReopen(@TempDir Path dir) {
    // given
    Path file = dir.resolve("sub/index.sqlite");
    try (SqliteIndex index = SqliteIndex.open(file)) {
      index.meta().set(SPACE, "key", "value");
    }

    // when
    Optional<String> value;
    try (SqliteIndex index = SqliteIndex.open(file)) {
      value = index.meta().get(SPACE, "key");
    }

    // then
    assertThat(value).contains("value");
  }

  @Nested
  @DisplayName("두 스레드가 같은 새 파일을 동시에 열면")
  class ConcurrentFirstOpen {
    private final CountDownLatch start = new CountDownLatch(1);
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private Future<SqliteIndex> openedA;
    private Future<SqliteIndex> openedB;

    @BeforeEach
    void givenTwoOpeners(@TempDir Path dir) {
      Path file = dir.resolve("index.sqlite");
      openedA = pool.submit(() -> openAfterStart(file));
      openedB = pool.submit(() -> openAfterStart(file));
    }

    @AfterEach
    void shutDown() {
      pool.shutdownNow();
    }

    @Test
    @DisplayName("두 열기가 성공하고 같은 스키마의 데이터를 공유한다")
    void bothOpenAndShareSchema() throws Exception {
      // when
      start.countDown();
      try (SqliteIndex first = openedA.get(OPEN_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
          SqliteIndex second = openedB.get(OPEN_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
        first.meta().set(SPACE, "key", "shared");

        // then
        assertThat(second.meta().get(SPACE, "key")).contains("shared");
      }
    }

    private SqliteIndex openAfterStart(Path file) throws InterruptedException {
      start.await();
      return SqliteIndex.open(file);
    }
  }

  @Nested
  @DisplayName("스키마가 이미 최신인 파일에 다른 연결이 쓰기 트랜잭션을 쥐고 있어도")
  class OpenWhileWriterHolds {
    private Path file;
    private Connection writer;

    @BeforeEach
    void givenHeldWriteTransaction(@TempDir Path dir) throws Exception {
      file = dir.resolve("index.sqlite");
      try (SqliteIndex index = SqliteIndex.open(file)) {
        index.meta().set(SPACE, "ready", "yes");
      }
      writer = DriverManager.getConnection("jdbc:sqlite:" + file);
      try (Statement st = writer.createStatement()) {
        st.execute("BEGIN IMMEDIATE");
        st.execute("INSERT INTO space_meta (space_id, key, value) VALUES ('s', 'k', 'v')");
      }
    }

    @AfterEach
    void releaseWriter() throws Exception {
      try (Statement st = writer.createStatement()) {
        st.execute("ROLLBACK");
      }
      writer.close();
    }

    @Test
    @DisplayName("쓰기 잠금을 기다리지 않고 바로 열린다")
    void opensPromptly() {
      // when
      long started = System.nanoTime();
      Optional<String> ready;
      try (SqliteIndex index = SqliteIndex.open(file)) {
        ready = index.meta().get(SPACE, "ready");
      }
      Duration took = Duration.ofNanos(System.nanoTime() - started);

      // then
      assertThat(ready).contains("yes");
      assertThat(took).isLessThan(PROMPT_OPEN);
    }
  }

  @Test
  @DisplayName("더 새 스키마의 파일은 열지 않는다")
  void refusesNewerSchema(@TempDir Path dir) throws Exception {
    // given
    Path file = dir.resolve("index.sqlite");
    try (var c = DriverManager.getConnection("jdbc:sqlite:" + file);
        var st = c.createStatement()) {
      st.execute("PRAGMA user_version = 99");
    }

    // when
    var opening = assertThatThrownBy(() -> SqliteIndex.open(file));

    // then
    opening
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("index.sqlite schema 99 is newer than 1");
  }
}
