package io.scos.mcp;

/** 도구 실패의 전송 노출 정책. 단일 사용자는 기존 문구를 보존한다. */
public enum ErrorExposure {
  RAW,
  MASKED
}
