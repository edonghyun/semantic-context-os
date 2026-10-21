package io.scos.spi.connector;

/** 어댑터가 정의한 변경 순서(TS SourceMutationOrder): 같은 scheme 안에서만 position 문자열(코드 유닛 순) 비교가 의미 있다. */
public record SourceOrder(String scheme, String position) {
  public SourceOrder {
    if (scheme == null || scheme.isBlank())
      throw new IllegalArgumentException("scheme must not be blank");
    if (position == null) throw new IllegalArgumentException("position must not be null");
  }
}
