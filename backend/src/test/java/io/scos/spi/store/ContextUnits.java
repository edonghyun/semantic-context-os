package io.scos.spi.store;

import java.util.List;
import java.util.Optional;

/**
 * {@link ContextUnit} 테스트 데이터 빌더(TESTING §3.2). 기본값은 테스트가 신경 쓰지 않는 값이다 — 결과가 어떤 값에 달려 있으면 호출부가 그 값을
 * 명시한다. 생성자 호출은 {@link Builder#build()} 한 곳뿐이다.
 */
public final class ContextUnits {
  public static final String DEFAULT_CAPTURED_AT = "2026-10-01T00:00:00.000Z";

  private ContextUnits() {}

  public static Builder unit(String id) {
    return new Builder(id);
  }

  /** 기존 유닛의 모든 필드를 복사한 빌더 — 일부만 바꾼 사본을 만들 때(수명주기 상태 준비 등). */
  public static Builder from(ContextUnit u) {
    Builder b = new Builder(u.id());
    b.partition = u.partition();
    b.title = u.title();
    b.content = u.content();
    b.contentHash = u.contentHash();
    b.tokens = u.tokens();
    b.tier = u.tier();
    b.sourceId = u.provenance().sourceId();
    b.documentId = u.provenance().documentId();
    b.uri = u.provenance().uri();
    b.capturedAt = u.provenance().capturedAt();
    b.importance = u.importance();
    b.validFrom = u.validFrom();
    b.invalidatedAt = u.invalidatedAt();
    b.links = u.links();
    b.tags = u.tags();
    b.aliases = u.aliases();
    b.embedding = u.embedding();
    b.compactedFrom = u.compactedFrom();
    return b;
  }

  public static final class Builder {
    private final String id;
    private String partition = "/knowledge";
    private String title;
    private String content;
    private String contentHash;
    private int tokens = 5;
    private Tier tier = Tier.RAW;
    private String sourceId = "fixture";
    private String documentId;
    private String uri;
    private String capturedAt = DEFAULT_CAPTURED_AT;
    private int importance = 5;
    private Optional<String> validFrom = Optional.empty();
    private Optional<String> invalidatedAt = Optional.empty();
    private List<String> links = List.of();
    private List<String> tags = List.of();
    private List<String> aliases = List.of();
    private Optional<double[]> embedding = Optional.empty();
    private List<String> compactedFrom = List.of();

    private Builder(String id) {
      this.id = id;
      this.title = "제목 " + id;
      this.content = "본문 " + id;
      this.contentHash = "h-" + id;
      this.documentId = "doc-" + id;
    }

    /** 출처 id(Provenance.sourceId). 기본 "fixture". */
    public Builder source(String sourceId) {
      this.sourceId = sourceId;
      return this;
    }

    public Builder partition(String partition) {
      this.partition = partition;
      return this;
    }

    public Builder importance(int importance) {
      this.importance = importance;
      return this;
    }

    public Builder validFrom(String validFrom) {
      this.validFrom = Optional.of(validFrom);
      return this;
    }

    public Builder tagged(String... tags) {
      this.tags = List.of(tags);
      return this;
    }

    public Builder aliased(String... aliases) {
      this.aliases = List.of(aliases);
      return this;
    }

    public Builder inDocument(String documentId) {
      this.documentId = documentId;
      return this;
    }

    public Builder titled(String title) {
      this.title = title;
      return this;
    }

    public Builder content(String content) {
      this.content = content;
      return this;
    }

    public Builder contentHash(String contentHash) {
      this.contentHash = contentHash;
      return this;
    }

    public Builder tokens(int tokens) {
      this.tokens = tokens;
      return this;
    }

    public Builder archived() {
      this.tier = Tier.ARCHIVED;
      return this;
    }

    /** 임베딩 없음(from(unit) 사본에서 벡터를 떼어낼 때). */
    public Builder withoutEmbedding() {
      this.embedding = Optional.empty();
      return this;
    }

    /** compacted 티어 + 원본 id 목록(비어 있으면 드릴다운 대상 없음). */
    public Builder compacted(String... originals) {
      this.tier = Tier.COMPACTED;
      this.compactedFrom = List.of(originals);
      return this;
    }

    /** 출처 uri(MCP 출처 분류 입력). 기본은 "fixture://<documentId>". */
    public Builder uri(String uri) {
      this.uri = uri;
      return this;
    }

    public Builder capturedAt(String capturedAt) {
      this.capturedAt = capturedAt;
      return this;
    }

    public Builder invalidatedAt(String invalidatedAt) {
      this.invalidatedAt = Optional.of(invalidatedAt);
      return this;
    }

    public Builder linksTo(String... documentIds) {
      this.links = List.of(documentIds);
      return this;
    }

    public Builder embedding(double... vector) {
      this.embedding = Optional.of(vector);
      return this;
    }

    public ContextUnit build() {
      return new ContextUnit(
          id,
          partition,
          title,
          content,
          contentHash,
          tokens,
          tier,
          new Provenance(
              sourceId, documentId, uri == null ? "fixture://" + documentId : uri, capturedAt),
          importance,
          validFrom,
          invalidatedAt,
          links,
          tags,
          aliases,
          embedding,
          compactedFrom);
    }
  }
}
