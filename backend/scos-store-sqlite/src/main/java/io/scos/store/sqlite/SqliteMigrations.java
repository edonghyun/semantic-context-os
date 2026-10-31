package io.scos.store.sqlite;

import io.scos.spi.connector.CancellationToken;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * SQLite 스키마 적용기: {@code PRAGMA user_version}과 {@code V<n>__*.sql}을 번호 순서로 적용한다. 버전을 트랜잭션 없이 먼저 읽어
 * 최신이면 쓰기 잠금을 잡지 않는다(다른 세션의 긴 쓰기 트랜잭션에 막히지 않게). 낮을 때만 두 프로세스가 같은 새 파일을 동시에 열 수 있어서 {@code BEGIN
 * IMMEDIATE} 안에서 버전을 다시 읽고 적용한다(대기는 {@link #MIGRATION_BUSY_MILLIS}).
 */
public final class SqliteMigrations {
  private static final Pattern NAME = Pattern.compile("V(\\d+)__.*\\.sql");
  private static final int SQLITE_BUSY = 5;
  private static final int WAL_ATTEMPTS = 100;
  private static final Duration WAL_RETRY = Duration.ofMillis(50);

  /** 일반 연결의 잠금 대기 상한. 마이그레이션은 별도 상한을 쓴다. */
  public static final int BUSY_MILLIS = 5000;

  /** 스키마 적용의 쓰기 잠금 대기 — 다른 세션의 쓰기 트랜잭션이 끝나기를 연결 기본값보다 오래 기다린다. */
  static final int MIGRATION_BUSY_MILLIS = 30_000;

  private final DataSource dataSource;
  private final String resourceDir;
  private final String databaseName;

  /** databaseName은 버전 거부 오류에 표시할 저장소 이름이다. */
  public SqliteMigrations(DataSource dataSource, String resourceDir, String databaseName) {
    this.dataSource = dataSource;
    this.resourceDir = resourceDir;
    this.databaseName = databaseName;
  }

  /** 스크립트 하나(버전 번호와 본문). */
  private record Script(int version, String sql) {}

  /**
   * 아는 최대 버전까지 적용한다.
   *
   * @return 이 호출이 빈 파일(버전 0)에서 스키마를 만들었으면 true
   * @throws SQLException SQL 적용이나 연결이 실패했을 때(호출자의 저장 경계가 문맥을 붙여 변환한다)
   * @throws IllegalStateException 저장된 버전이 아는 최대보다 클 때
   */
  public boolean apply() throws SQLException {
    List<Script> scripts = load();
    int max = scripts.isEmpty() ? 0 : scripts.get(scripts.size() - 1).version();
    try (Connection c = dataSource.getConnection();
        Statement st = c.createStatement()) {
      c.setAutoCommit(true);
      int seen = userVersion(st);
      if (seen > max) throw newer(seen, max);
      if (seen == max) return false;
      st.execute("PRAGMA busy_timeout = " + MIGRATION_BUSY_MILLIS);
      enableWal(st);
      st.execute("BEGIN IMMEDIATE");
      try {
        int current = userVersion(st);
        if (current > max) throw newer(current, max);
        for (Script s : scripts) {
          if (s.version() <= current) continue;
          for (String statement : statements(s.sql())) st.execute(statement);
          st.execute("PRAGMA user_version = " + s.version());
        }
        st.execute("COMMIT");
        return current == 0 && max > 0;
      } catch (RuntimeException | SQLException e) {
        rollbackQuietly(st, e);
        throw e;
      }
    }
  }

  /**
   * WAL 전환은 파일에 남는 설정이라 처음 한 번이면 된다. 다른 프로세스가 스키마를 만드는 중이면 전환이 busy_timeout 대기 없이 SQLITE_BUSY로
   * 실패하므로 직접 다시 시도한다.
   */
  private static void enableWal(Statement st) throws SQLException {
    CancellationToken waiting = CancellationToken.none();
    for (int attempt = 1; ; attempt++) {
      try {
        st.execute("PRAGMA journal_mode = WAL");
        return;
      } catch (SQLException e) {
        if (e.getErrorCode() != SQLITE_BUSY || attempt >= WAL_ATTEMPTS) throw e;
        if (waiting.awaitCancellation(WAL_RETRY)) throw e;
      }
    }
  }

  private IllegalStateException newer(int current, int max) {
    return new IllegalStateException(databaseName + " schema " + current + " is newer than " + max);
  }

  private static int userVersion(Statement st) throws SQLException {
    try (var rs = st.executeQuery("PRAGMA user_version")) {
      return rs.next() ? rs.getInt(1) : 0;
    }
  }

  private static void rollbackQuietly(Statement st, Exception cause) {
    try {
      st.execute("ROLLBACK");
    } catch (SQLException e) {
      cause.addSuppressed(e);
    }
  }

  private List<Script> load() {
    try {
      Resource[] found =
          new PathMatchingResourcePatternResolver()
              .getResources("classpath*:" + resourceDir + "/V*__*.sql");
      return Arrays.stream(found)
          .map(SqliteMigrations::script)
          .sorted(Comparator.comparingInt(Script::version))
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Script script(Resource r) {
    String name = r.getFilename();
    Matcher m = NAME.matcher(name == null ? "" : name);
    if (!m.matches()) throw new IllegalStateException("bad migration name: " + name);
    try (var input = r.getInputStream()) {
      return new Script(
          Integer.parseInt(m.group(1)), new String(input.readAllBytes(), StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** 줄 주석을 빼고 세미콜론으로 문장을 나눈다 — 스크립트에는 문자열 안 세미콜론을 쓰지 않는다. */
  private static List<String> statements(String sql) {
    StringBuilder body = new StringBuilder();
    sql.lines()
        .filter(line -> !line.stripLeading().startsWith("--"))
        .forEach(line -> body.append(line).append('\n'));
    return Pattern.compile(";")
        .splitAsStream(body)
        .map(String::strip)
        .filter(s -> !s.isEmpty())
        .toList();
  }
}
