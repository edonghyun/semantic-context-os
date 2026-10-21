package io.scos.spi.connector;

/** 어댑터(Slack 등)가 소유하는 재개 위치. 형식은 어댑터의 저장 호환 계약이라 JSON 원문으로 보관한다(CONTRACTS F12). */
public record Checkpoint(int schemaVersion, String valueJson) {
  public Checkpoint {
    if (schemaVersion <= 0) throw new IllegalArgumentException("schemaVersion must be positive");
    if (valueJson == null || valueJson.isBlank())
      throw new IllegalArgumentException("valueJson must not be blank");
  }
}
