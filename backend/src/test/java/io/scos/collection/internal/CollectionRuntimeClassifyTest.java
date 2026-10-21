package io.scos.collection.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.CallFailureException;
import io.scos.spi.connector.CredentialResolver;
import io.scos.spi.connector.RawContentTooLargeException;
import io.scos.spi.connector.RunErrorKind;
import java.sql.SQLException;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

/** run 실패 종류(error_kind) 분류 — 저장소 일시 장애는 `storage_failed`(ADR-0020 §4), 그 밖은 예외 종류 이름. */
@DisplayName("run 실패 종류 분류 — 저장소 일시 장애는 storage_failed(ADR-0020 §4)")
class CollectionRuntimeClassifyTest {
  /** 캡처 상한과 그것을 넘은 크기(값은 분류와 무관). */
  static final long LIMIT = 1;

  static final long CAPTURED = 2;

  /** 저장소 일시 장애: SQLState 55P03·40P01·40001, 일시 DataAccess 예외(blob GC 경합 번역 포함), 연결 풀 고갈. */
  static Stream<Arguments> transientStorageErrors() {
    return Stream.of(
        Arguments.of("55P03 잠금 대기 초과(원인 사슬)", wrap("55P03"), "storage_failed"),
        Arguments.of("40P01 교착", wrap("40P01"), "storage_failed"),
        Arguments.of("40001 직렬화 실패", wrap("40001"), "storage_failed"),
        Arguments.of(
            "blob GC 경합(어댑터가 일시 장애로 번역)",
            new TransientDataAccessResourceException("blob referenced while being collected"),
            "storage_failed"),
        Arguments.of(
            "Spring CannotAcquireLock", new CannotAcquireLockException("lock"), "storage_failed"),
        Arguments.of("Spring QueryTimeout", new QueryTimeoutException("slow"), "storage_failed"),
        Arguments.of(
            "연결 풀 고갈",
            new CannotGetJdbcConnectionException("pool exhausted", new SQLException("timeout")),
            "storage_failed"));
  }

  /** 저장소 일시 장애가 아닌 예외 — 결함으로 드러나야 하므로 예외 종류 이름을 남긴다. */
  static Stream<Arguments> otherErrors() {
    return Stream.of(
        Arguments.of("23503 FK 위반 일반(결함으로 드러남)", wrap("23503"), "RuntimeException"),
        Arguments.of("23505 유일성 위반", wrap("23505"), "RuntimeException"),
        Arguments.of(
            "DuplicateKey(비일시)", new DuplicateKeyException("dup"), "DuplicateKeyException"),
        Arguments.of("일반 예외", new IllegalStateException("boom"), "IllegalStateException"));
  }

  static RuntimeException wrap(String sqlState) {
    return new RuntimeException("db", new SQLException("state " + sqlState, sqlState));
  }

  @ParameterizedTest(name = "{0} → {2}")
  @MethodSource("transientStorageErrors")
  @DisplayName("일시적인 저장소 오류는 storage_failed로 분류한다")
  void classifiesTransientStorageErrors(String name, RuntimeException error, String expected) {
    // when
    String kind = CollectionRuntime.classify(error);

    // then
    assertThat(kind).isEqualTo(expected);
  }

  @ParameterizedTest(name = "{0} → {2}")
  @MethodSource("otherErrors")
  @DisplayName("저장소 일시 장애가 아닌 예외는 예외 종류 이름으로 분류한다")
  void classifiesOtherErrorsByName(String name, RuntimeException error, String expected) {
    // when
    String kind = CollectionRuntime.classify(error);

    // then
    assertThat(kind).isEqualTo(expected);
  }

  /** TS가 일반 Error로 던지는 경우의 Java 예외(커넥터 봉투 위반 등)를 흉내 낸 것. */
  static final class TsPlainError extends RuntimeException implements RunErrorKind {
    TsPlainError() {
      super("Invalid collection response");
    }

    @Override
    public String runErrorKind() {
      return "Error";
    }
  }

  @Test
  @DisplayName("TS 값을 아는 예외(RunErrorKind)는 그 값을 남긴다")
  void recordsTsErrorKind() {
    // when
    String kind = CollectionRuntime.classify(new TsPlainError());

    // then
    assertThat(kind).isEqualTo("Error");
  }

  @Test
  @DisplayName("원본 캡처 상한 초과는 TS 클래스 이름 RawContentTooLarge로 남긴다")
  void recordsRawContentTooLargeAsTsName() {
    // when
    String kind = CollectionRuntime.classify(new RawContentTooLargeException(CAPTURED, LIMIT));

    // then
    assertThat(kind).isEqualTo("RawContentTooLarge");
  }

  @Test
  @DisplayName("공급자 호출 실패는 그 분류 종류를 남긴다")
  void recordsProviderFailureKind() {
    // given
    CallFailureException failure =
        new CallFailureException(
            "jira.read", CallFailure.of(CallFailure.Kind.PERMISSION_DENIED, "denied"));

    // when
    String kind = CollectionRuntime.classify(failure);

    // then
    assertThat(kind).isEqualTo("permission_denied");
  }

  @Test
  @DisplayName("자격 증명 설정 오류는 TS 값 Error로 남긴다")
  void recordsCredentialMisconfigurationAsTsError() {
    // given: TS 해석기는 일반 Error를 던진다(CONTRACTS "run errorKind 매핑"). 재시도하지 않는 분류는 그대로
    // invalid_configuration이다
    CallFailureException failure =
        credentialFailure(CallFailure.Kind.INVALID_CONFIGURATION, "env:TOKEN is not set");

    // when
    String kind = CollectionRuntime.classify(failure);

    // then
    assertThat(kind).isEqualTo("Error");
  }

  @Test
  @DisplayName("자격 증명 해석이 중단되면 cancelled로 남긴다")
  void recordsCancelledCredentialResolution() {
    // given
    CallFailureException failure =
        credentialFailure(CallFailure.Kind.CANCELLED, "command: interrupted");

    // when
    String kind = CollectionRuntime.classify(failure);

    // then
    assertThat(kind).isEqualTo("cancelled");
  }

  @Test
  @DisplayName("자격 증명이 아닌 설정 오류는 invalid_configuration으로 남긴다")
  void recordsOtherMisconfigurationKind() {
    // given
    CallFailureException failure =
        new CallFailureException(
            "slack.config",
            CallFailure.of(CallFailure.Kind.INVALID_CONFIGURATION, "channel missing"));

    // when
    String kind = CollectionRuntime.classify(failure);

    // then
    assertThat(kind).isEqualTo("invalid_configuration");
  }

  @Test
  @DisplayName("공급자 호출 실패의 요약은 실패 메시지 그대로다")
  void summarizesProviderFailureByItsMessage() {
    // given: TS classifyRunError가 ProviderCallError면 failure.message를 쓴다 — source status가 TS와 같은
    // 문구를 보인다
    CallFailureException failure =
        new CallFailureException(
            "jira.read",
            CallFailure.of(
                CallFailure.Kind.DEPENDENCY_UNAVAILABLE, "Jira request failed (HTTP 503)"));

    // when
    String summary = CollectionRuntime.summary(failure);

    // then
    assertThat(summary).isEqualTo("Jira request failed (HTTP 503)");
  }

  @Test
  @DisplayName("그 밖의 예외의 요약은 예외 메시지다")
  void summarizesOtherErrorsByMessage() {
    // when
    String summary = CollectionRuntime.summary(new IllegalStateException("boom"));

    // then
    assertThat(summary).isEqualTo("boom");
  }

  @Test
  @DisplayName("메시지 없는 예외의 요약은 빈 문자열이다")
  void summarizesMessagelessErrorAsEmpty() {
    // when: TS classifyRunError는 error.message("")를 그대로 쓴다 — 종류 이름은 errorKind가 이미 싣는다
    String summary = CollectionRuntime.summary(new IllegalStateException());

    // then
    assertThat(summary).isEmpty();
  }

  static CallFailureException credentialFailure(CallFailure.Kind kind, String message) {
    return new CallFailureException(CredentialResolver.OPERATION, CallFailure.of(kind, message));
  }
}
