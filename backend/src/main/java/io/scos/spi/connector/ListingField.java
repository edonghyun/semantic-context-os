package io.scos.spi.connector;

/**
 * 소스 목록 항목 끝에 붙는 어댑터 고유 필드 하나(stage2 §2.4, TS: Slack의 slackSelection). 값은 JSON 원문이다 — 어댑터가 JSON
 * 라이브러리 타입을 몰라도 되게 한다. JSON이 아니면 목록을 만드는 런타임이 거부한다.
 */
public record ListingField(String name, String valueJson) {
  public ListingField {
    if (name == null || name.isBlank())
      throw new IllegalArgumentException("name must not be blank");
  }
}
