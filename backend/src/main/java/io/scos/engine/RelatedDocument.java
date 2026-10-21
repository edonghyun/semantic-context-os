package io.scos.engine;

/** 이웃 문서 한 건. via는 점수에 기여한 채널이다. */
public record RelatedDocument(String documentId, String title, String uri, double score, Via via) {
  public enum Via {
    EMBEDDING,
    LINK,
    BOTH
  }
}
