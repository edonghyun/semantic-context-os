package io.scos.spi.connector;

import java.util.Locale;

/** TS SourceDeletionDetection: 원격 삭제가 로컬 tombstone이 되는 경로(소스 목록이 보여 준다). */
public enum DeletionDetection {
  /** 삭제를 알 길이 없다 — 부재로 지우지 않는다(Jira 등). */
  NONE,
  /** 공급자 이벤트가 삭제를 알린다(Slack 이벤트 서명 비밀이 설정된 경우). */
  PROVIDER_EVENTS,
  /** 완전 스냅샷의 부재가 삭제 근거다(F5). */
  COMPLETE_SNAPSHOT;

  public String wire() {
    return name().toLowerCase(Locale.ROOT);
  }
}
