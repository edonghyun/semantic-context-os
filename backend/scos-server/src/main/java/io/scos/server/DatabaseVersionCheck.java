package io.scos.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 기동 시 DB 버전 검사(T10.1, ADR-0027, {@code collection.SourceStartupCheck}와 같은 방식): 서버 major.minor와
 * pgvector 확장 버전이 기대값과 하나라도 다르면 {@link IllegalStateException}으로 기동을 거부한다. 기본 기대값은 테스트가 쓰는 digest 고정
 * 이미지의 값이다 — 운영이 검증하지 않은 버전에서 조용히 도는 일을 막는다. 올릴 때는 이미지 digest와 기대값(환경변수)을 함께 바꾼다.
 */
public final class DatabaseVersionCheck {
  /** 기대 버전: 서버 "major.minor", pgvector extversion. */
  public record Expected(String server, String vector) {
    public static final Expected DEFAULT = new Expected("17.11", "0.8.7");
  }

  /** 관측 버전. pgvector 확장이 없으면 vector는 empty. */
  public record Observed(String server, Optional<String> vector) {}

  private final Expected expected;
  private final Supplier<Observed> observed;

  public DatabaseVersionCheck(Expected expected, Supplier<Observed> observed) {
    this.expected = expected;
    this.observed = observed;
  }

  public void verify() {
    Observed actual = observed.get();
    List<String> mismatches = new ArrayList<>();
    if (!actual.server().equals(expected.server()))
      mismatches.add("server " + actual.server() + " (expected " + expected.server() + ")");
    if (!actual.vector().equals(Optional.of(expected.vector())))
      mismatches.add(
          "vector " + actual.vector().orElse("missing") + " (expected " + expected.vector() + ")");
    if (!mismatches.isEmpty())
      throw new IllegalStateException(
          "database version mismatch: "
              + String.join(", ", mismatches)
              + " — set SCOS_DB_EXPECTED_SERVER_VERSION·SCOS_DB_EXPECTED_VECTOR_VERSION only after verifying the new image");
  }

  /** server_version_num(예: 170011) → "17.11", pg_extension의 vector extversion. */
  public static Observed observe(DataSource dataSource) {
    JdbcClient jdbc = JdbcClient.create(dataSource);
    int number =
        jdbc.sql("SELECT current_setting('server_version_num')::int").query(Integer.class).single();
    Optional<String> vector =
        jdbc.sql("SELECT extversion FROM pg_extension WHERE extname = 'vector'")
            .query(String.class)
            .optional();
    return new Observed(number / 10_000 + "." + number % 10_000, vector);
  }
}
