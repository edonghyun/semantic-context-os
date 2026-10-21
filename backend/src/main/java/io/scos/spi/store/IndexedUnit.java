package io.scos.spi.store;

/** 색인에 넣는 유닛 벡터. 적재 순서는 어댑터가 부여한다(seq). 배열 컴포넌트는 생성자에서 복사하고 외부로 돌려주지 않는다(어댑터만 읽는다). */
@SuppressWarnings("ArrayRecordComponent")
public record IndexedUnit(
    String unitId, String documentId, String partition, boolean archived, double[] embedding) {
  public IndexedUnit {
    if (unitId == null || unitId.isBlank())
      throw new IllegalArgumentException("unitId must not be blank");
    if (documentId == null || documentId.isBlank())
      throw new IllegalArgumentException("documentId must not be blank");
    if (embedding == null) throw new IllegalArgumentException("embedding must not be null");
    partition = partition == null ? "/knowledge" : partition;
    embedding = embedding.clone();
  }
}
