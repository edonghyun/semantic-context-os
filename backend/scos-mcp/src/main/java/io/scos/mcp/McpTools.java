package io.scos.mcp;

import io.scos.engine.AppliedPlan;
import io.scos.engine.AssembleQuery;
import io.scos.engine.ContextBundle;
import io.scos.engine.ContextEngine;
import io.scos.engine.ContextEngineException;
import io.scos.engine.DocumentNotFoundException;
import io.scos.engine.DocumentPage;
import io.scos.engine.Effort;
import io.scos.engine.EmbeddingFailedException;
import io.scos.engine.Freshness;
import io.scos.engine.InvalidQueryException;
import io.scos.engine.Profile;
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
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectWriter;

/**
 * MCP 도구 네 개의 핸들러(검색·문서 읽기·관련 문서·조립의 응답 매핑). 읽기 파사드 {@link ContextEngine}만 부른다 — 인자 검사·클램프는 지금처럼
 * 여기서 먼저 하고, 요청자는 전송이 호출마다 전달한다. SDK와 무관한 응답 매핑이라 메모리 조립으로 테스트한다. 결과는 `JSON.stringify(result, null,
 * 2)`와 같은 모양·키 순서의 JSON 문자열(wire 레코드). 문서 없음·인자 범위는 {@link ToolError}, 모델 호출 실패는 {@link
 * ToolFailure}로 구분하고 서버가 isError로 싣는다. 그 밖의 예외(인프라)는 그대로 전파된다(리뷰 M-5).
 */
public final class McpTools {
  public static final int DEFAULT_LIMIT = 5;
  public static final int MIN_LIMIT = 1;
  public static final int MAX_LIMIT = 10;
  static final int EXCERPT_LENGTH = 200;
  private static final IntRange LIMITS = IntRange.between(MIN_LIMIT, MAX_LIMIT);
  private static final IntRange TOKEN_BUDGETS =
      IntRange.between(AssembleQuery.MIN_TOKEN_BUDGET, AssembleQuery.MAX_TOKEN_BUDGET);
  private static final List<String> EFFORTS = List.of("low", "medium", "high");
  private static final List<String> PROFILES = List.of("auto", "current");

  /** 클라이언트 요청의 계약 거부(메시지 = RAW 와이어 문구). */
  public static final class ToolError extends RuntimeException {
    ToolError(@Nullable String message) {
      super(message);
    }
  }

  /** 공급자 장애. RAW는 공급자 문구를 유지하고 MASKED는 내부 실패로 보고한다. */
  public static final class ToolFailure extends RuntimeException {
    ToolFailure(Throwable cause) {
      super(cause.getMessage(), cause);
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

  public String assembleContext(Principal principal, Map<String, Object> rawArgs) {
    ToolArguments args = new ToolArguments("assemble_context", rawArgs);
    String query = args.requiredText("query");
    int budget =
        args.optionalInt("tokenBudget", IntRange.any())
            .map(TOKEN_BUDGETS::clamp)
            .orElse(AssembleQuery.DEFAULT_TOKEN_BUDGET);
    Effort effort = effort(args);
    Profile profile = profile(args);
    ContextBundle bundle =
        guard(
            () ->
                engine.assemble(
                    principal,
                    new AssembleQuery(Optional.empty(), query, budget, effort, profile)));
    List<Wire.AssembleUnit> units =
        bundle.items().stream()
            .map(
                i ->
                    new Wire.AssembleUnit(
                        i.title(),
                        SourceKind.of(i.uri()).wire(),
                        i.documentId(),
                        i.capturedAt(),
                        i.content()))
            .toList();
    return json.writeValueAsString(
        new Wire.AssembleResult(
            units,
            bundle.usedTokens(),
            bundle.truncated(),
            freshness(bundle.freshness()),
            applied(bundle.applied())));
  }

  public String searchContext(Principal principal, Map<String, Object> rawArgs) {
    ToolArguments args = new ToolArguments("search_context", rawArgs);
    String query = args.requiredText("query");
    int limit = clampLimit(args.optionalInt("limit", IntRange.any()));
    Effort effort = effort(args);
    Profile profile = profile(args);
    SearchResult result =
        guard(
            () ->
                engine.search(
                    principal, new SearchQuery(Optional.empty(), query, limit, effort, profile)));
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
    queryLog.append(principal, query, hits);
    return json.writeValueAsString(
        new Wire.SearchResult(hits, freshness(result.freshness()), applied(result.applied())));
  }

  public String readDocument(Principal principal, Map<String, Object> rawArgs) {
    ToolArguments args = new ToolArguments("read_document", rawArgs);
    String documentId = args.requiredText("documentId");
    int offset = args.optionalInt("offset", IntRange.atLeast(0)).orElse(0);
    int limit =
        args.optionalInt("limit", IntRange.between(1, ReadRequest.MAX_LIMIT))
            .orElse(ReadRequest.DEFAULT_LIMIT);
    DocumentPage page =
        guard(
            () ->
                engine.read(
                    principal, new ReadRequest(Optional.empty(), documentId, offset, limit)));
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

  public String relatedDocuments(Principal principal, Map<String, Object> rawArgs) {
    ToolArguments args = new ToolArguments("related_documents", rawArgs);
    String documentId = args.requiredText("documentId");
    int limit = clampLimit(args.optionalInt("limit", IntRange.any()));
    List<RelatedDocument> found =
        guard(
                () ->
                    engine.related(
                        principal, new RelatedQuery(Optional.empty(), documentId, limit)))
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

  /** 엔진 실패의 종류와 RAW 와이어 문구를 보존한다. */
  private static <T> T guard(Supplier<T> body) {
    try {
      return body.get();
    } catch (ContextEngineException e) {
      throw switch (e) {
        case DocumentNotFoundException d -> new ToolError("문서를 찾을 수 없음: " + d.documentId());
        // 원인은 생성자가 받은 공급자 예외다 — requireNonNull은 실패하지 않는다.
        case EmbeddingFailedException f -> new ToolFailure(Objects.requireNonNull(f.getCause()));
        case InvalidQueryException q -> new ToolError(q.getMessage());
      };
    }
  }

  private static Effort effort(ToolArguments args) {
    return args.optionalChoice("effort", EFFORTS)
        .map(e -> Effort.valueOf(e.toUpperCase(Locale.ROOT)))
        .orElse(Effort.MEDIUM);
  }

  private static Profile profile(ToolArguments args) {
    return args.optionalChoice("profile", PROFILES)
        .map(p -> p.equals("current") ? Profile.CURRENT : Profile.AUTO)
        .orElse(Profile.AUTO);
  }

  private static Wire.Applied applied(AppliedPlan plan) {
    String profile =
        switch (plan.profile()) {
          case Profile.Auto a -> "auto";
          case Profile.Current c -> "current";
          case Profile.AsOf o -> "as-of";
        };
    return new Wire.Applied(
        plan.effort().name().toLowerCase(Locale.ROOT), profile, plan.escalated());
  }

  static int clampLimit(Optional<Integer> limit) {
    return limit.map(LIMITS::clamp).orElse(DEFAULT_LIMIT);
  }

  /** 개행을 공백으로, 앞 200 UTF-16 코드 유닛(JS slice와 같은 단위, CONTRACTS F-2). */
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
