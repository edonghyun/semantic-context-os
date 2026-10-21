package io.scos.mcp;

import io.scos.engine.AssembleQuery;
import io.scos.engine.ContextBundle;
import io.scos.engine.ContextEngine;
import io.scos.engine.ContextEngineException;
import io.scos.engine.DocumentNotFoundException;
import io.scos.engine.DocumentPage;
import io.scos.engine.EmbeddingFailedException;
import io.scos.engine.Freshness;
import io.scos.engine.InvalidQueryException;
import io.scos.engine.ReadRequest;
import io.scos.engine.RelatedDocument;
import io.scos.engine.RelatedQuery;
import io.scos.engine.SearchQuery;
import io.scos.engine.SearchResult;
import io.scos.mcp.wire.Wire;
import io.scos.spi.store.Principal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectWriter;

/**
 * MCP 도구 네 개의 핸들러(TS search-tool·read-document-tool·related-documents-tool·assemble의 응답 매핑). 읽기 파사드
 * {@link ContextEngine}만 부른다 — 인자 검사·클램프는 지금처럼 여기서 먼저 하고, stdio는 단일 사용자라 {@link
 * Principal#singleUser()}로 묻는다. SDK와 무관한 순수 매핑이라 메모리 조립으로 테스트한다. 결과는 TS `JSON.stringify(result,
 * null, 2)`와 같은 키 순서의 JSON 문자열(wire 레코드). 계약 위반(문서 없음·인자 범위·모델 호출 실패, {@link
 * ContextEngineException})은 {@link ToolError}로 — 서버가 isError로 싣는다. 그 밖의 예외(인프라)는 그대로 전파된다(리뷰 M-5).
 */
public final class McpTools {
  public static final int DEFAULT_LIMIT = 5;
  public static final int MIN_LIMIT = 1;
  public static final int MAX_LIMIT = 10;
  static final int EXCERPT_LENGTH = 200;
  private static final Principal PRINCIPAL = Principal.singleUser();

  /** isError 응답으로 보낼 실패(메시지 = TS 와이어 문구). */
  public static final class ToolError extends RuntimeException {
    ToolError(String message) {
      super(message);
    }
  }

  private final ContextEngine engine;
  private final ObjectWriter json;
  private final QueryLog queryLog;

  public McpTools(ContextEngine engine, ObjectMapper mapper, QueryLog queryLog) {
    this.engine = engine;
    this.json = mapper.writerWithDefaultPrettyPrinter();
    this.queryLog = queryLog;
  }

  public String assembleContext(Map<String, Object> rawArgs) {
    ToolArguments args = new ToolArguments("assemble_context", rawArgs);
    String query = args.requiredText("query");
    int budget =
        args.optionalInt("tokenBudget", null, null)
            .map(
                b ->
                    Math.min(
                        AssembleQuery.MAX_TOKEN_BUDGET,
                        Math.max(AssembleQuery.MIN_TOKEN_BUDGET, b)))
            .orElse(AssembleQuery.DEFAULT_TOKEN_BUDGET);
    ContextBundle bundle =
        guard(() -> engine.assemble(PRINCIPAL, new AssembleQuery(Optional.empty(), query, budget)));
    List<Wire.AssembleUnit> units =
        bundle.items().stream()
            .map(
                i ->
                    new Wire.AssembleUnit(
                        i.title(), SourceKind.of(i.uri()).wire(), i.documentId(), i.content()))
            .toList();
    return json.writeValueAsString(
        new Wire.AssembleResult(
            units, bundle.usedTokens(), bundle.truncated(), freshness(bundle.freshness())));
  }

  public String searchContext(Map<String, Object> rawArgs) {
    ToolArguments args = new ToolArguments("search_context", rawArgs);
    String query = args.requiredText("query");
    int limit = clampLimit(args.optionalInt("limit", null, null));
    SearchResult result =
        guard(() -> engine.search(PRINCIPAL, new SearchQuery(Optional.empty(), query, limit)));
    List<Wire.SearchHit> hits =
        result.hits().stream()
            .map(
                h ->
                    new Wire.SearchHit(
                        h.title(),
                        excerpt(h.content()),
                        SourceKind.of(h.uri()).wire(),
                        h.documentId(),
                        h.capturedAt(),
                        round3(h.score())))
            .toList();
    queryLog.append(query, hits);
    return json.writeValueAsString(new Wire.SearchResult(hits, freshness(result.freshness())));
  }

  public String readDocument(Map<String, Object> rawArgs) {
    ToolArguments args = new ToolArguments("read_document", rawArgs);
    String documentId = args.requiredText("documentId");
    int offset = args.optionalInt("offset", 0, null).orElse(0);
    int limit =
        args.optionalInt("limit", 1, ReadRequest.MAX_LIMIT).orElse(ReadRequest.DEFAULT_LIMIT);
    DocumentPage page =
        guard(
            () ->
                engine.read(
                    PRINCIPAL, new ReadRequest(Optional.empty(), documentId, offset, limit)));
    List<Wire.UnitView> units =
        page.units().stream().map(v -> new Wire.UnitView(v.title(), v.content())).toList();
    return json.writeValueAsString(
        new Wire.ReadPage(
            page.title(),
            SourceKind.of(page.uri()).wire(),
            page.uri(),
            units,
            page.nextOffset().isPresent() ? page.nextOffset().getAsInt() : null));
  }

  public String relatedDocuments(Map<String, Object> rawArgs) {
    ToolArguments args = new ToolArguments("related_documents", rawArgs);
    String documentId = args.requiredText("documentId");
    int limit = clampLimit(args.optionalInt("limit", null, null));
    List<RelatedDocument> found =
        guard(
                () ->
                    engine.related(
                        PRINCIPAL, new RelatedQuery(Optional.empty(), documentId, limit)))
            .documents();
    List<Wire.RelatedItem> items =
        found.stream()
            .map(
                r ->
                    new Wire.RelatedItem(
                        r.documentId(),
                        r.title(),
                        SourceKind.of(r.uri()).wire(),
                        round3(r.score()),
                        r.via().name().toLowerCase(Locale.ROOT)))
            .toList();
    return json.writeValueAsString(new Wire.RelatedResult(items));
  }

  /** 엔진 계약 위반 → TS 와이어 문구. 문서 없음은 documentId로, 임베딩 실패는 공급자 예외의 메시지 그대로다. */
  private static <T> T guard(java.util.function.Supplier<T> body) {
    try {
      return body.get();
    } catch (ContextEngineException e) {
      throw new ToolError(
          switch (e) {
            case DocumentNotFoundException d -> "문서를 찾을 수 없음: " + d.documentId();
            case EmbeddingFailedException f -> f.getCause().getMessage();
            case InvalidQueryException q -> q.getMessage();
          });
    }
  }

  static int clampLimit(Optional<Integer> limit) {
    return limit.map(l -> Math.min(MAX_LIMIT, Math.max(MIN_LIMIT, l))).orElse(DEFAULT_LIMIT);
  }

  /** 개행을 공백으로, 앞 200 UTF-16 코드 유닛(TS slice와 같은 단위, CONTRACTS F-2). */
  static String excerpt(String content) {
    String flat = content.replaceAll("\r?\n", " ");
    return flat.length() <= EXCERPT_LENGTH ? flat : flat.substring(0, EXCERPT_LENGTH);
  }

  static double round3(double x) {
    return Math.round(x * 1000) / 1000.0;
  }

  private static Wire.FreshnessView freshness(Freshness f) {
    return new Wire.FreshnessView(
        f.latestCapturedAt().orElse(null), f.staleHours().orElse(null), f.stale());
  }
}
