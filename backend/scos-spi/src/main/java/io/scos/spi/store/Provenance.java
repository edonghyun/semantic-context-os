package io.scos.spi.store;

/**
 * 출처. capturedAt은 TS와 같이 ISO 문자열로 둔다 — 최신성은 문자열 최댓값(DocumentCatalog), 날짜 연산은 사용처에서 파싱한다(CONTRACTS
 * F-4).
 */
public record Provenance(String sourceId, String documentId, String uri, String capturedAt) {
  public Provenance {
    if (documentId == null || documentId.isBlank())
      throw new IllegalArgumentException("documentId must not be blank");
    sourceId = sourceId == null ? "" : sourceId;
    uri = uri == null ? "" : uri;
    capturedAt = capturedAt == null ? "" : capturedAt;
  }
}
