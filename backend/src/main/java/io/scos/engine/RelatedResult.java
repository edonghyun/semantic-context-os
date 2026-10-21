package io.scos.engine;

import java.util.List;

/** related 결과: 점수 내림차순(동점은 documentId 순). */
public record RelatedResult(List<RelatedDocument> documents) {
  public RelatedResult {
    documents = List.copyOf(documents);
  }
}
