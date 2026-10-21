package io.scos.spi.store;

/** 데이터 공간. 호스트가 의미를 주는 불투명 id이며 인가 토큰이 아니다(CONTRACTS A). 모든 포트 메서드의 첫 인자다. */
public record SpaceId(String value) {
  public static final SpaceId LEGACY = new SpaceId("legacy");

  public SpaceId {
    if (value == null || value.isBlank())
      throw new IllegalArgumentException("spaceId must not be blank");
  }

  @Override
  public String toString() {
    return value;
  }
}
