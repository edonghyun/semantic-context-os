package io.scos.spi.connector;

/**
 * run에 기록할 errorKind를 스스로 아는 예외(CONTRACTS "run errorKind 매핑"). `source_runs.error_kind`는 운영 조회와 데이터
 * 이전에서 TS 값과 비교되는 저장 필드라, TS가 일반 `Error`로 던지는 경우(커넥터 응답 봉투 위반 등)는 Java 예외 클래스 이름이 아니라 TS
 * 값("Error")을 남긴다.
 */
public interface RunErrorKind {
  String runErrorKind();
}
