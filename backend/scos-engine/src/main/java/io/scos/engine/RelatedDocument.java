package io.scos.engine;

/** 이웃 문서 한 건. sourceId는 이웃 문서의 원천 id, via는 점수에 기여한 채널이다. */
public record RelatedDocument(
    String documentId, String title, String uri, String sourceId, double score, Via via) {
  public enum Via {
    EMBEDDING,
    LINK,
    BOTH
  }
}
