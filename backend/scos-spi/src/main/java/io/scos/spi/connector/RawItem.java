package io.scos.spi.connector;

import java.time.Instant;
import java.util.Optional;

/**
 * 원본 항목 한 건(TS RawSourceItem, F4). 키는 (소스, externalId) — 소스는 저장소 호출의 인자다. metadata는 어댑터 소유 JSON.
 */
public record RawItem(
    String externalId,
    String resourceType,
    String sourceVersion,
    Optional<SourceOrder> sourceOrder,
    Optional<String> sourceCreatedAt,
    Optional<String> sourceUpdatedAt,
    Instant observedAt,
    String metadataJson,
    Optional<RawContent> content) {
  public RawItem {
    if (externalId == null || externalId.isBlank())
      throw new IllegalArgumentException("externalId must not be blank");
    if (resourceType == null || resourceType.isBlank())
      throw new IllegalArgumentException("resourceType must not be blank");
    if (sourceVersion == null || sourceVersion.isBlank())
      throw new IllegalArgumentException("sourceVersion must not be blank");
    if (observedAt == null) throw new IllegalArgumentException("observedAt must not be null");
    sourceOrder = sourceOrder == null ? Optional.empty() : sourceOrder;
    sourceCreatedAt = sourceCreatedAt == null ? Optional.empty() : sourceCreatedAt;
    sourceUpdatedAt = sourceUpdatedAt == null ? Optional.empty() : sourceUpdatedAt;
    metadataJson = metadataJson == null ? "{}" : metadataJson;
    content = content == null ? Optional.empty() : content;
  }

  /** 테스트·실험용 최소형: 메타데이터만 있는 항목. */
  public static RawItem minimal(
      String externalId, String sourceVersion, String metadataJson, Instant observedAt) {
    return new RawItem(
        externalId,
        "test.item",
        sourceVersion,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        observedAt,
        metadataJson,
        Optional.empty());
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
        Optional.of(c));
  }
}
