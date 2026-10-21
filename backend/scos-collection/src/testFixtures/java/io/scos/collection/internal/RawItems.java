package io.scos.collection.internal;

import io.scos.spi.connector.RawContent;
import io.scos.spi.connector.RawItem;
import io.scos.spi.connector.RawMutation;
import io.scos.spi.connector.SourceOrder;
import java.time.Instant;
import java.util.Optional;

/** 테스트 데이터 빌더(TESTING §3.2): 기본값이 드러나고 위치 인자·null이 없다. */
public final class RawItems {
  public static final Instant T0 = Instant.parse("2026-10-14T00:00:00Z");

  private String externalId;
  private String resourceType = "slack.message";
  private String version = "v1";
  private Optional<SourceOrder> order = Optional.empty();
  private Instant observedAt = T0;
  private String metadataJson = "{}";
  private Optional<RawContent> content = Optional.empty();

  private RawItems(String externalId) {
    this.externalId = externalId;
  }

  public static RawItems item(String externalId) {
    return new RawItems(externalId);
  }

  public RawItems version(String v) {
    this.version = v;
    return this;
  }

  /** `slack.message-state.v1` 순서 위치. */
  public RawItems at(String position) {
    this.order = Optional.of(new SourceOrder("slack.message-state.v1", position));
    return this;
  }

  public RawItems order(SourceOrder o) {
    this.order = Optional.of(o);
    return this;
  }

  public RawItems resourceType(String t) {
    this.resourceType = t;
    return this;
  }

  public RawItems observedAt(Instant t) {
    this.observedAt = t;
    return this;
  }

  public RawItems metadata(String json) {
    this.metadataJson = json;
    return this;
  }

  public RawItems inlineJson(String json) {
    this.content = Optional.of(new RawContent.Inline("application/json", json));
    return this;
  }

  public RawItems content(RawContent c) {
    this.content = Optional.of(c);
    return this;
  }

  public RawItem build() {
    return new RawItem(
        externalId,
        resourceType,
        version,
        order,
        Optional.empty(),
        Optional.empty(),
        observedAt,
        metadataJson,
        content);
  }

  public RawMutation.Upsert upsert() {
    return new RawMutation.Upsert(build());
  }
}
