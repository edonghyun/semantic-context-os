package io.scos.provider.openai;

/** OpenAI 호환 어댑터 테스트의 가짜 서버 경로(서버 시작·실행기는 {@link io.scos.support.ProviderCallFakes}). */
final class OpenAiCompatibleFakes {
  /** 가짜 서버 위의 OpenAI 호환 base URL(끝 슬래시 없음). */
  static final String API_PATH = "/v1";

  private OpenAiCompatibleFakes() {}
}
