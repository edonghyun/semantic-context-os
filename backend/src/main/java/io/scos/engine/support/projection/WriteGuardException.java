package io.scos.engine.support.projection;

/**
 * 쓰기 guard가 이 쓰기를 거부했다(엔진 쪽 기반 예외, 예: 수집의 점유 상실) — sync는 문서 격리 없이 그대로 전파한다(문서 실패가 아니라 run 실패).
 * guard의 다른 예외는 그 문서만 격리한다. 종류와 상관없이 전파하는 규칙은 ContextIngestor 단계에서 정한다(공개 배포 단계 3 T4).
 */
public class WriteGuardException extends RuntimeException {
  public WriteGuardException(String message) {
    super(message);
  }

  public WriteGuardException(String message, Throwable cause) {
    super(message, cause);
  }
}
