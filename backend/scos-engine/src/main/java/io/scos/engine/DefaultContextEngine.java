package io.scos.engine;

import io.scos.engine.internal.search.ContextAssembler;
import io.scos.engine.internal.search.DocumentReader;
import io.scos.engine.internal.search.FreshnessReader;
import io.scos.engine.internal.search.QuestionSearch;
import io.scos.engine.internal.search.RelatedDocuments;
import io.scos.engine.internal.search.RerankOutcome;
import io.scos.spi.embedding.Embedder;
import io.scos.spi.store.AccessScope;
import io.scos.spi.store.ContextUnit;
import io.scos.spi.store.Principal;
import io.scos.spi.store.SpaceId;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 읽기 파사드 구현: 질의 범위를 검사하고 내부 레시피(QuestionSearch·DocumentReader·RelatedDocuments·ContextAssembler·
 * FreshnessReader)에 맡긴 뒤 공개 값으로 옮긴다. principal은 접근 범위로 검색 레시피까지 전달만 한다(0.1.0, 필터 없음).
 */
final class DefaultContextEngine implements ContextEngine {
  static final int MIN_LIMIT = 1;
  private static final Logger log = LoggerFactory.getLogger(DefaultContextEngine.class);

  private final SpaceId defaultSpace;
  private final QuestionSearch search;
  private final DocumentReader reader;
  private final RelatedDocuments related;
  private final ContextAssembler assembler;
  private final FreshnessReader freshness;

  DefaultContextEngine(
      SpaceId defaultSpace,
      QuestionSearch search,
      DocumentReader reader,
      RelatedDocuments related,
      ContextAssembler assembler,
      FreshnessReader freshness) {
    this.defaultSpace = defaultSpace;
    this.search = search;
    this.reader = reader;
    this.related = related;
    this.assembler = assembler;
    this.freshness = freshness;
  }

  @Override
  public SearchResult search(Principal principal, SearchQuery query) {
    QuestionSearch.Result result = searchRecipe(principal, query);
    return new SearchResult(
        result.candidates().stream().limit(query.limit()).map(DefaultContextEngine::hit).toList(),
        result.freshness(),
        status(result.rerank()));
  }

  /** 검사 → 검색 레시피(재정렬 폴백은 경고로 남긴다). search가 앞에서 limit개를 자른다. */
  QuestionSearch.Result searchRecipe(Principal principal, SearchQuery query) {
    AccessScope access = AccessScope.unfiltered(principal);
    requireText(query.text());
    requireRange("limit", query.limit(), MIN_LIMIT, SearchQuery.MAX_LIMIT);
    QuestionSearch.Result result =
        embedding(() -> search.search(access, space(query.space()), query.text()));
    if (result.rerank() instanceof RerankOutcome.FallbackError f)
      log.warn("rerank fallback (provider error): {}", f.message());
    if (result.rerank() instanceof RerankOutcome.FallbackFormat)
      log.warn("rerank fallback (invalid selection format)");
    return result;
  }

  @Override
  public DocumentPage read(Principal principal, ReadRequest request) {
    Objects.requireNonNull(principal, "principal");
    requireDocumentId(request.documentId());
    if (request.offset() < 0)
      throw new InvalidQueryException("offset", "must be non-negative: " + request.offset());
    requireRange("limit", request.limit(), MIN_LIMIT, ReadRequest.MAX_LIMIT);
    DocumentReader.Page page =
        reader.read(
            space(request.space()), request.documentId(), request.offset(), request.limit());
    return new DocumentPage(
        request.documentId(),
        page.title(),
        page.uri(),
        page.units().stream().map(v -> new UnitView(v.unitId(), v.title(), v.content())).toList(),
        page.nextOffset().map(OptionalInt::of).orElse(OptionalInt.empty()));
  }

  @Override
  public RelatedResult related(Principal principal, RelatedQuery query) {
    Objects.requireNonNull(principal, "principal");
    requireDocumentId(query.documentId());
    requireRange("limit", query.limit(), MIN_LIMIT, RelatedQuery.MAX_LIMIT);
    return new RelatedResult(
        related.find(space(query.space()), query.documentId(), query.limit()).stream()
            .map(
                r ->
                    new RelatedDocument(
                        r.documentId(),
                        r.title(),
                        r.uri(),
                        r.score(),
                        RelatedDocument.Via.valueOf(r.via().name())))
            .toList());
  }

  @Override
  public ContextBundle assemble(Principal principal, AssembleQuery query) {
    AccessScope access = AccessScope.unfiltered(principal);
    requireText(query.text());
    requireRange(
        "tokenBudget",
        query.tokenBudget(),
        AssembleQuery.MIN_TOKEN_BUDGET,
        AssembleQuery.MAX_TOKEN_BUDGET);
    SpaceId space = space(query.space());
    ContextAssembler.Bundle bundle =
        embedding(() -> assembler.assemble(access, space, query.text(), query.tokenBudget()));
    return new ContextBundle(
        bundle.items().stream().map(DefaultContextEngine::item).toList(),
        bundle.usedTokens(),
        bundle.truncated(),
        freshness.read(space));
  }

  @Override
  public Freshness freshness(Principal principal, SpaceId space) {
    Objects.requireNonNull(principal, "principal");
    return freshness.read(Objects.requireNonNull(space, "space"));
  }

  @Override
  public SpaceId defaultSpace() {
    return defaultSpace;
  }

  private SpaceId space(Optional<SpaceId> space) {
    return space.orElse(defaultSpace);
  }

  /** 질의 임베딩 실패만 공개 예외로 옮긴다. 그 밖(인프라)은 그대로 전파한다(리뷰 M-5). */
  private static <T> T embedding(Supplier<T> recipe) {
    try {
      return recipe.get();
    } catch (Embedder.EmbedderException e) {
      throw new EmbeddingFailedException(e);
    }
  }

  /**
   * 빈 문자열만 거부한다. 공백뿐인 질의는 지금 MCP(zod min(1))가 받아 검색하므로, 엔진이 거부하면 MCP 와이어가 바뀐다 — 단계 2 §1.2의 "공백 아닌
   * 문자열"보다 좁다.
   */
  private static void requireText(String text) {
    if (text == null || text.isEmpty())
      throw new InvalidQueryException("text", "must not be empty");
  }

  /** 빈 문자열만 거부한다(공백뿐인 id는 지금처럼 "문서 없음"이다). */
  private static void requireDocumentId(String documentId) {
    if (documentId == null || documentId.isEmpty())
      throw new InvalidQueryException("documentId", "must not be empty");
  }

  private static void requireRange(String field, int value, int min, int max) {
    if (value < min || value > max)
      throw new InvalidQueryException(
          field, "must be between " + min + " and " + max + ": " + value);
  }

  private static ContextHit hit(QuestionSearch.Candidate c) {
    ContextUnit u = c.unit();
    return new ContextHit(
        u.id(),
        u.documentId(),
        u.title(),
        u.content(),
        u.provenance().uri(),
        u.provenance().sourceId(),
        u.provenance().capturedAt(),
        u.tier(),
        c.baseScore());
  }

  private static BundleItem item(ContextAssembler.Item i) {
    ContextUnit u = i.unit();
    return new BundleItem(
        u.id(), u.documentId(), u.title(), u.content(), u.provenance().uri(), i.rankScore());
  }

  static RerankStatus status(RerankOutcome outcome) {
    return switch (outcome) {
      case RerankOutcome.Applied a -> new RerankStatus(RerankStatus.Kind.APPLIED, a.fromCache());
      case RerankOutcome.Skipped s -> new RerankStatus(RerankStatus.Kind.SKIPPED, false);
      case RerankOutcome.FallbackError e ->
          new RerankStatus(RerankStatus.Kind.FALLBACK_PROVIDER_ERROR, false);
      case RerankOutcome.FallbackFormat f ->
          new RerankStatus(RerankStatus.Kind.FALLBACK_INVALID_FORMAT, false);
    };
  }
}
