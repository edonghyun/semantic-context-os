package io.scos.mcp;

/** 도구 응답의 source를 정하는 규칙. 기본은 {@link #URI_ONLY}다(CONTRACTS B10). */
public enum SourceLabels {
  /** uri 분류값. 분류할 수 없으면 unknown. */
  URI_ONLY,
  /** uri로 분류할 수 없으면 provenance.sourceId, 그것도 비면 unknown. 원천 id가 사이트 이름인 인스턴스(게시판 보관소)용. */
  SOURCE_ID_FALLBACK;

  String label(String uri, String sourceId) {
    SourceKind kind = SourceKind.of(uri);
    if (this == SOURCE_ID_FALLBACK && kind == SourceKind.UNKNOWN && !sourceId.isBlank())
      return sourceId;
    return kind.wire();
  }
}
