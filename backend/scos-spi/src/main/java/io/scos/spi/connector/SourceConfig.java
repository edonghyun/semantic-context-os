package io.scos.spi.connector;

/** 소스 설정: 어댑터 소유 JSON 값과 그 스키마 버전. */
public record SourceConfig(int schemaVersion, String valueJson) {
  public SourceConfig {
    if (schemaVersion <= 0) throw new IllegalArgumentException("schemaVersion must be positive");
    if (valueJson == null || valueJson.isBlank())
      throw new IllegalArgumentException("valueJson must not be blank");
  }
}
