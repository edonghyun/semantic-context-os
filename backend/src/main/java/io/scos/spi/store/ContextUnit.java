package io.scos.spi.store;

import java.util.List;
import java.util.Optional;

/**
 * 컨텍스트 유닛(TS ContextUnit의 Java 값). 임베딩은 입력에만 실리고 저장은 벡터 색인이 맡는다(VectorIndex). links는 대상 documentId
 * 목록. 컬렉션은 방어 복사한다.
 */
public record ContextUnit(
    String id,
    String partition,
    String title,
    String content,
    String contentHash,
    int tokens,
    Tier tier,
    Provenance provenance,
    int importance,
    Optional<String> validFrom,
    Optional<String> invalidatedAt,
    List<String> links,
    List<String> tags,
    List<String> aliases,
    Optional<double[]> embedding,
    List<String> compactedFrom) {
  public ContextUnit {
    if (id == null || id.isBlank()) throw new IllegalArgumentException("id must not be blank");
    if (partition == null || partition.isBlank())
      throw new IllegalArgumentException("partition must not be blank");
    if (tier == null) throw new IllegalArgumentException("tier");
    if (provenance == null) throw new IllegalArgumentException("provenance");
    title = title == null ? "" : title;
    content = content == null ? "" : content;
    contentHash = contentHash == null ? "" : contentHash;
    validFrom = validFrom == null ? Optional.empty() : validFrom;
    invalidatedAt = invalidatedAt == null ? Optional.empty() : invalidatedAt;
    links = links == null ? List.of() : List.copyOf(links);
    tags = tags == null ? List.of() : List.copyOf(tags);
    aliases = aliases == null ? List.of() : List.copyOf(aliases);
    embedding = embedding == null ? Optional.empty() : embedding.map(double[]::clone);
    compactedFrom = compactedFrom == null ? List.of() : List.copyOf(compactedFrom);
  }

  public String documentId() {
    return provenance.documentId();
  }

  public boolean isInvalidated() {
    return invalidatedAt.isPresent();
  }
}
