package io.scos.spi.connector;

/** 커넥터가 원본 내용을 저장 형태({@link RawContent})로 바꾸는 계약. 제품 구현은 수집 런타임의 {@code RawContentCapture}다. */
public interface ContentCapture {
  /** JSON 값(직렬화된 원문). */
  RawContent json(String mediaType, String valueJson);

  /** 바이트 원본(첨부 등). */
  RawContent bytes(String mediaType, byte[] bytes);
}
