package io.scos.spi.access;

import java.util.Locale;

/** 권한을 닫은 원인의 허용 목록. 원문·개인정보·공급자 응답을 담지 않는다. */
public enum AccessFailureReason {
  NONE,
  UNSPECIFIED,
  UNKNOWN,
  PROVIDER_FAILURE,
  ACL_UNREADABLE,
  VISIBILITY_UNVERIFIED,
  EXPIRED,
  MISSING_TARGET,
  INVALID_CARRIER,
  INVALID_INSTANCE,
  UNSUPPORTED_SOURCE,
  NO_CARRIERS,
  UNOBSERVED_CARRIER,
  GROUPING_FAILED,
  RENDER_FAILED,
  DOCUMENT_MISSING,
  OWNERSHIP_CONFLICT,
  CONFLICTING_OBSERVATIONS;

  /** 새 바이너리가 저장한 원인도 상태·키는 그대로 읽을 수 있다. */
  public static AccessFailureReason fromStored(String value) {
    try {
      return valueOf(value);
    } catch (IllegalArgumentException unknown) {
      return UNKNOWN;
    }
  }

  public String wire() {
    return name().toLowerCase(Locale.ROOT);
  }
}
