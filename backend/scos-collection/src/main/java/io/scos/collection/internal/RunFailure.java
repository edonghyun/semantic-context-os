package io.scos.collection.internal;

import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.CallFailureException;
import io.scos.spi.connector.CredentialResolver;
import io.scos.spi.connector.RunErrorKind;
import java.sql.SQLException;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

/**
 * run을 멈춘 예외가 기록에 남는 모양: 오류 종류(error_kind)와 요약. 종류는 공급자 호출 실패면 그 분류, 예외가 아는 값(RunErrorKind),
 * 점유·체크포인트·run 상태 위반, 저장소 일시 장애(storage_failed), 그 밖은 예외 클래스 이름. 요약은 공급자 호출 실패면 실패 메시지
 * 그대로(operation·종류 접두 없이), 그 밖은 예외 메시지, 없으면 빈 문자열이다. 공급자 호출 실패면 실패 위치(operation, 커넥터가 붙인
 * providerLocator)와 응답 검사 위반도 들고 다닌다 — 실패 이벤트에만 싣고 저장하지 않는다.
 */
public record RunFailure(
    String kind,
    String summary,
    Optional<String> operation,
    Optional<String> providerLocatorJson,
    Optional<CallFailure.Violation> violation) {
  /**
   * 잠금 대기 초과 55P03·교착 40P01·직렬화 실패 40001. FK 위반 23503은 넣지 않는다 — blob GC 경합(blob FK 제약)만 어댑터가 일시 장애
   * 예외로 번역하고(`collection.postgres.BlobReferences`), 그 밖의 FK 위반은 결함으로 드러난다.
   */
  private static final Set<String> TRANSIENT_SQL_STATES = Set.of("55P03", "40P01", "40001");

  public static RunFailure of(RuntimeException error) {
    String kind = kindOf(error);
    if (error instanceof CallFailureException p)
      return new RunFailure(
          kind,
          p.failure().message(),
          Optional.of(p.operation()),
          p.providerLocatorJson(),
          p.failure().violation());
    String message = error.getMessage();
    return new RunFailure(
        kind, message == null ? "" : message, Optional.empty(), Optional.empty(), Optional.empty());
  }

  /** 로그에 싣는 요약(저장 상한과 같은 길이). */
  String loggedSummary() {
    return summary.substring(0, Math.min(summary.length(), RunOutcome.SUMMARY_CHARS));
  }

  private static String kindOf(RuntimeException error) {
    if (error instanceof CallFailureException p) return providerFailureKind(p);
    if (error instanceof RunErrorKind k) return k.runErrorKind();
    if (error instanceof ClaimLostException) return "claim_lost";
    if (error instanceof CheckpointConflictException) return "checkpoint_conflict";
    if (error instanceof RunStateException) return "run_state";
    if (isStorageFailure(error)) return "storage_failed";
    return error.getClass().getSimpleName();
  }

  /**
   * 저장값은 기록된 계약대로, 내부 분류는 Java 타입대로(ADR-0022 §2): 자격 증명 설정 오류는 "Error"로 남는다. 재시도하지 않는 판단은 그대로
   * invalid_configuration이 한다.
   */
  private static String providerFailureKind(CallFailureException failure) {
    CallFailure.Kind kind = failure.failure().kind();
    if (failure.operation().equals(CredentialResolver.OPERATION)
        && kind == CallFailure.Kind.INVALID_CONFIGURATION) return "Error";
    return kind.wire();
  }

  /**
   * 저장소의 일시 장애(ADR-0020 §4): {@link #TRANSIENT_SQL_STATES}, Spring의 일시 DataAccess 예외, 연결 풀
   * 고갈(CannotGetJdbcConnection). 재시도 가능 — 감독 backoff가 다시 시도한다. 원인 사슬을 따라가며 SQLState로 본다(메시지 정규식
   * 금지).
   */
  private static boolean isStorageFailure(Throwable error) {
    for (Throwable t = error; t != null; t = t.getCause()) {
      if (t instanceof TransientDataAccessException
          || t instanceof CannotGetJdbcConnectionException) return true;
      if (t instanceof SQLException sql && TRANSIENT_SQL_STATES.contains(sql.getSQLState()))
        return true;
    }
    return false;
  }
}
