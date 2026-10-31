package io.scos.provider.openai;

/** 필수 설정 누락의 메시지 한 형식: 운영자가 고칠 환경변수 이름과 그것을 요구한 공급자 선택을 함께 말한다. */
final class RequiredSetting {
  private RequiredSetting() {}

  /** 예: {@code SCOS_EMBEDDER_DIM is required when SCOS_EMBEDDER_PROVIDER=openai-compatible}. */
  static IllegalArgumentException missing(String role, String name) {
    return new IllegalArgumentException(
        role + "_" + name + " is required when " + role + "_PROVIDER=openai-compatible");
  }
}
