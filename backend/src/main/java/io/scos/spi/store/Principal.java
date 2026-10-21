package io.scos.spi.store;

/**
 * 묻는 사람(다중 사용자 접근 설계 §5): 어댑터가 인증으로 확인한 조직 안의 사용자 id. 엔진은 받기만 하고 인증하지 않는다. 0.1.0은 접근 필터가 없어 검색 경로에
 * 그대로 전달만 한다(§8).
 */
public record Principal(String userId) {
  /** 단일 사용자 모드의 고정 id(MCP stdio). */
  public static final String SINGLE_USER_ID = "local";

  private static final Principal SINGLE_USER = new Principal(SINGLE_USER_ID);

  public Principal {
    if (userId == null || userId.isBlank())
      throw new IllegalArgumentException("userId must not be blank");
  }

  /** 단일 사용자 모드의 principal — 인증 없는 stdio 어댑터가 쓴다. */
  public static Principal singleUser() {
    return SINGLE_USER;
  }
}
