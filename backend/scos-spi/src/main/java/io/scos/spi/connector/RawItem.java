package io.scos.spi.connector;

import java.time.Instant;
import java.util.Optional;

/** 원본 항목 한 건(F4). 키는 (소스, externalId) — 소스는 저장소 호출의 인자다. metadata는 어댑터 소유 JSON. */
public record RawItem(
    String externalId,
    String resourceType,
    String sourceVersion,
    Optional<SourceOrder> sourceOrder,
    Optional<String> sourceCreatedAt,
    Optional<String> sourceUpdatedAt,
    Instant observedAt,
    String metadataJson,
    Optional<RawContent> content,
    RawAcl acl) {
  public RawItem {
    if (externalId == null || externalId.isBlank())
      throw new IllegalArgumentException("externalId must not be blank");
    if (resourceType == null || resourceType.isBlank())
      throw new IllegalArgumentException("resourceType must not be blank");
    if (sourceVersion == null || sourceVersion.isBlank())
      throw new IllegalArgumentException("sourceVersion must not be blank");
    if (observedAt == null) throw new IllegalArgumentException("observedAt must not be null");
    if (acl == null) throw new IllegalArgumentException("acl must not be null");
    sourceOrder = sourceOrder == null ? Optional.empty() : sourceOrder;
    sourceCreatedAt = sourceCreatedAt == null ? Optional.empty() : sourceCreatedAt;
    sourceUpdatedAt = sourceUpdatedAt == null ? Optional.empty() : sourceUpdatedAt;
    metadataJson = metadataJson == null ? "{}" : metadataJson;
    content = content == null ? Optional.empty() : content;
  }

  public RawItem withContent(RawContent c) {
    return new RawItem(
        externalId,
        resourceType,
        sourceVersion,
        sourceOrder,
        sourceCreatedAt,
        sourceUpdatedAt,
        observedAt,
        metadataJson,
        Optional.of(c),
        acl);
  }
}
