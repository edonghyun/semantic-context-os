package io.scos.spi.store;

/** 키워드 색인에 넣는 유닛 본문. 적재 순서(seq)는 어댑터가 부여한다. */
public record IndexedText(
    String unitId,
    String documentId,
    String title,
    String content,
    String partition,
    boolean archived) {
  public IndexedText {
    if (unitId == null || unitId.isBlank())
      throw new IllegalArgumentException("unitId must not be blank");
    if (documentId == null || documentId.isBlank())
      throw new IllegalArgumentException("documentId must not be blank");
    title = title == null ? "" : title;
    content = content == null ? "" : content;
    partition = partition == null ? "/" : partition;
  }
}
