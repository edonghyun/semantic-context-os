package io.scos.spi.projection;

/** 투영기가 만든 문서(TS SourceDocument, contracts/src/types.ts:5-15). 파이프라인 입력. 시각은 ISO 문자열. */
public record SourceDocument(
    String sourceId,
    String documentId,
    String uri,
    String title,
    String body,
    String createdAt,
    String updatedAt) {
  public SourceDocument {
    if (documentId == null || documentId.isBlank())
      throw new IllegalArgumentException("documentId must not be blank");
    sourceId = sourceId == null ? "" : sourceId;
    uri = uri == null ? "" : uri;
    title = title == null ? "" : title;
    body = body == null ? "" : body;
    createdAt = createdAt == null ? "" : createdAt;
    updatedAt = updatedAt == null ? "" : updatedAt;
  }

  public SourceDocument withBody(String newBody) {
    return new SourceDocument(sourceId, documentId, uri, title, newBody, createdAt, updatedAt);
  }
}
