package io.scos.spi.connector;

/** 수집 연결 식별자. 빈 값은 계약 위반이다. */
public record SourceInstanceId(String value) {
  public SourceInstanceId {
    if (value == null || value.isBlank())
      throw new IllegalArgumentException("sourceInstanceId must not be blank");
  }

  @Override
  public String toString() {
    return value;
  }
}
