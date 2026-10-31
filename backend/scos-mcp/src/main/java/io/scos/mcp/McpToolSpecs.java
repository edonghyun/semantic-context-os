package io.scos.mcp;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.scos.engine.EventLog;
import io.scos.spi.store.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 도구 명세 한 벌 — stdio와 데몬 HTTP가 같은 와이어를 쓴다. 입력 스키마는 tools/list 녹화본(옛
 * experiments/pa/results/ts-mcp-wire.json, f93f5966 이전 이력) 기반이며 effort·profile 옵션은 녹화본 이후 확장이다.
 * RAW는 기존 계약 오류 문구·인프라 예외 전파를 유지하고, MASKED는 실패 원문을 참조 id 뒤로 감춘다.
 */
public final class McpToolSpecs {
  private static final Logger LOG = LoggerFactory.getLogger(McpToolSpecs.class);
  private static final int REFERENCE_LENGTH = 12;
  public static final String PRINCIPAL_KEY = "scos.principal";
  public static final String SERVER_NAME = "scos-mcp-server";
  public static final String SERVER_VERSION = "0.0.1";

  /**
   * initialize 응답의 instructions — 클라이언트(Claude Code 등)가 에이전트 시스템 프롬프트에 싣는다. 도구가 지연 로딩되면 에이전트는 도구
   * 이름만 보므로 "언제 쓰는가"를 여기서 말한다. 모든 프로젝트의 세션 프롬프트에 들어가므로 짧게 둔다. 서버 이름·instructions는 계약이 아니다(ADR-0010,
   * CONTRACTS J) — 녹화본(tools/list 입력 스키마)과 하네스 비교 대상이 아니다.
   */
  public static final String INSTRUCTIONS =
      "SCOS는 이 조직 내부의 Slack 대화, Notion 문서, Jira 티켓, Google Drive 문서, 회의록을 모은 사내 지식 검색 서버입니다"
          + " (this organization's internal knowledge only: decisions, specs, incidents, runbooks, meeting notes).\n"
          + "언제 쓰나: 저장소 코드·git 기록만으로는 답이 없는 사내 맥락이 필요할 때 — 왜 그렇게 결정했는지, 사내 정책·규칙·용어, "
          + "다른 팀·시스템의 사양, 과거 장애와 대응, 티켓·논의의 진행 상황 — 추측하거나 사용자에게 되묻기 전에 먼저 여기서 찾으세요. "
          + "사용자가 Slack·Notion·Jira·Drive·회의에서의 논의를 언급하면 바로 쓰세요. "
          + "공개 라이브러리·프레임워크·외부 API 문서는 여기 없으니 찾지 마세요. 코드만 보면 되는 작업에는 부르지 않아도 됩니다.\n"
          + "어떻게: 배경을 모으려면 assemble_context 한 번(토큰 예산 안에서 본문까지)부터. "
          + "후보를 훑어 고르려면 search_context, 고른 문서 전체는 read_document, "
          + "한 문서와 이어진 스레드·문서·유사 사례는 related_documents.\n"
          + "계정·비밀번호·주소·설정값·담당자처럼 시간에 따라 바뀌는 값을 물을 때는 profile=current로 관련 결과를 최신순으로 조회하세요.\n"
          + "질의는 핵심 명사(서비스·기능 이름, 티켓 키)로 짧게. 응답의 freshness.stale=true면 데이터가 12시간 이상 묵었으니 답에 밝히고, "
          + "인용할 때는 title과 source를 함께 적으세요.";

  private static final String INT_RANGE =
      "\"minimum\":-9007199254740991,\"maximum\":9007199254740991";
  private static final String OPTIONS =
      "\"effort\":{\"type\":\"string\",\"enum\":[\"low\",\"medium\",\"high\"],\"description\":\"검색 깊이. low=빠른 조회, medium=기본, high=결정 전 조사(느림)\"},"
          + "\"profile\":{\"type\":\"string\",\"enum\":[\"auto\",\"current\"],\"description\":\"우선순위. auto=질의로 판단, current=관련 결과를 최신순으로\"}";
  static final String SEARCH_SCHEMA =
      "{\"$schema\":\"http://json-schema.org/draft-07/schema#\",\"type\":\"object\",\"properties\":{"
          + "\"query\":{\"type\":\"string\",\"minLength\":1,\"description\":\"검색할 질문 또는 키워드\"},"
          + "\"limit\":{\"description\":\"반환할 결과 수 (기본 5, 최대 10으로 제한됨)\",\"type\":\"integer\","
          + INT_RANGE
          + "},"
          + OPTIONS
          + "},\"required\":[\"query\"]}";
  static final String READ_SCHEMA =
      "{\"$schema\":\"http://json-schema.org/draft-07/schema#\",\"type\":\"object\",\"properties\":{"
          + "\"documentId\":{\"type\":\"string\",\"minLength\":1,\"description\":\"search_context 결과의 documentId\"},"
          + "\"offset\":{\"description\":\"시작 위치, 기본 0\",\"type\":\"integer\",\"minimum\":0,\"maximum\":9007199254740991},"
          + "\"limit\":{\"description\":\"페이지당 유닛 수, 기본 20\",\"type\":\"integer\",\"minimum\":1,\"maximum\":100}},\"required\":[\"documentId\"]}";
  static final String RELATED_SCHEMA =
      "{\"$schema\":\"http://json-schema.org/draft-07/schema#\",\"type\":\"object\",\"properties\":{"
          + "\"documentId\":{\"type\":\"string\",\"minLength\":1,\"description\":\"관련 문서를 찾을 기준 documentId\"},"
          + "\"limit\":{\"description\":\"반환할 결과 수 (기본 5, 최대 10으로 제한됨)\",\"type\":\"integer\","
          + INT_RANGE
          + "}},\"required\":[\"documentId\"]}";

  static final String ASSEMBLE_SCHEMA =
      "{\"$schema\":\"http://json-schema.org/draft-07/schema#\",\"type\":\"object\",\"properties\":{"
          + "\"query\":{\"type\":\"string\",\"minLength\":1,\"description\":\"컨텍스트를 조립할 질문 또는 키워드\"},"
          + "\"tokenBudget\":{\"description\":\"조립할 토큰 예산 (기본 2000, 500~8000 범위로 클램프됨)\",\"type\":\"integer\","
          + INT_RANGE
          + "},"
          + OPTIONS
          + "},\"required\":[\"query\"]}";
  static final String ASSEMBLE_DESCRIPTION =
      "사내 지식(팀 Slack 대화, Notion 문서, Jira 티켓, Google Drive 문서, 회의록)에서 질문의 배경을 모을 때 먼저 부르는 도구입니다 — "
          + "결정 이유, 사내 규칙·사양, 과거 장애와 논의처럼 코드에 없는 맥락. "
          + "질의로 예산 내 최적 컨텍스트 묶음을 한 번에 조립합니다. search_context→read_document "
          + "왕복 없이, 검색·랭킹·토큰 예산 배치를 한 호출로 마쳐 제목·출처·문서 id·본문이 담긴 유닛 목록을 반환합니다. "
          + "freshness.stale=true면 데이터가 12시간 이상 묵었음을 알리고, latestCapturedAt=null은 최신성 확인 불가로 해석하세요. "
          + "tokenBudget은 기본 2000이며 500~8000 범위로 자동 조정됩니다. "
          + "계정·설정값처럼 바뀌는 값은 profile=current로 관련 결과를 최신순으로 조회하세요. "
          + "effort는 빠른 확인이면 low, 결정 전 조사면 high를 쓰세요(기본 medium).";
  static final String SEARCH_DESCRIPTION =
      "팀 Slack 채널, Notion 워크스페이스, Jira 프로젝트, Google Drive 문서 지식에서 의미 기반 검색으로 관련 문서 조각을 찾습니다. "
          + "어떤 문서·논의가 있는지 훑어 고를 때 씁니다 — 배경을 본문까지 한 번에 모으려면 assemble_context가 낫습니다. "
          + "질문이나 키워드로 질의하면 제목·발췌·출처·문서 id·점수가 담긴 상위 결과를 반환합니다. "
          + "freshness.stale=true면 데이터가 12시간 이상 묵었음을 알리고, latestCapturedAt=null은 최신성 확인 불가로 해석하세요. "
          + "특정 문서의 전체 내용이 필요하면 반환된 documentId로 read_document를 호출하세요. "
          + "계정·설정값처럼 바뀌는 값은 profile=current로 관련 결과를 최신순으로 조회하세요. "
          + "effort는 빠른 확인이면 low, 결정 전 조사면 high를 쓰세요(기본 medium).";
  static final String READ_DESCRIPTION =
      "search_context·assemble_context·related_documents 결과의 documentId로 문서를 페이지 단위로 읽습니다. nextOffset이 null이 아니면 그 값을 offset으로 넘겨 다음 페이지를 읽으세요.";
  static final String RELATED_DESCRIPTION =
      "질의 없이 특정 문서와 관련된 다른 Slack 스레드·Notion 문서를 찾습니다. "
          + "같은 주제를 다룬 과거 논의, 유사 장애 사례, Slack↔Notion 짝 문서를 찾을 때 유용합니다. documentId는 search_context 또는 assemble_context 결과에서 얻습니다.";

  private final List<Definition> definitions;

  public McpToolSpecs(McpTools tools) {
    definitions = definitions(tools, McpServerProfile.ORGANIZATION);
  }

  public static ServerCapabilities capabilities() {
    return ServerCapabilities.builder().tools(true).build();
  }

  public static List<McpServerFeatures.SyncToolSpecification> sync(McpTools tools) {
    return sync(tools, McpServerProfile.ORGANIZATION);
  }

  static List<McpServerFeatures.SyncToolSpecification> sync(
      McpTools tools, McpServerProfile profile) {
    return definitions(tools, profile).stream()
        .map(
            d ->
                McpServerFeatures.SyncToolSpecification.builder()
                    .tool(d.tool())
                    .callHandler(
                        (exchange, request) ->
                            call(d, Principal.singleUser(), request.arguments(), ErrorExposure.RAW))
                    .build())
        .toList();
  }

  public List<McpStatelessServerFeatures.SyncToolSpecification> stateless(ErrorExposure exposure) {
    return definitions.stream()
        .map(
            d ->
                McpStatelessServerFeatures.SyncToolSpecification.builder()
                    .tool(d.tool())
                    .callHandler(
                        (context, request) -> {
                          if (!(context.get(PRINCIPAL_KEY) instanceof Principal principal))
                            return error("인증 정보가 없습니다");
                          return call(d, principal, request.arguments(), exposure);
                        })
                    .build())
        .toList();
  }

  private record Definition(
      Tool tool, BiFunction<Principal, Map<String, Object>, String> handler) {}

  private static List<Definition> definitions(McpTools tools, McpServerProfile profile) {
    return List.of(
        definition(
            "search_context", profile, SEARCH_DESCRIPTION, SEARCH_SCHEMA, tools::searchContext),
        definition("read_document", profile, READ_DESCRIPTION, READ_SCHEMA, tools::readDocument),
        definition(
            "related_documents",
            profile,
            RELATED_DESCRIPTION,
            RELATED_SCHEMA,
            tools::relatedDocuments),
        definition(
            "assemble_context",
            profile,
            ASSEMBLE_DESCRIPTION,
            ASSEMBLE_SCHEMA,
            tools::assembleContext));
  }

  private static Definition definition(
      String name,
      McpServerProfile profile,
      String defaultDescription,
      String schema,
      BiFunction<Principal, Map<String, Object>, String> handler) {
    return new Definition(
        Tool.builder()
            .name(name)
            .description(profile.toolDescriptions().getOrDefault(name, defaultDescription))
            .inputSchema(McpJsonDefaults.getMapper(), schema)
            .build(),
        handler);
  }

  private static CallToolResult call(
      Definition definition,
      Principal principal,
      Map<String, Object> arguments,
      ErrorExposure exposure) {
    try {
      return CallToolResult.builder()
          .content(List.of(new TextContent(definition.handler().apply(principal, arguments))))
          .build();
    } catch (Throwable e) {
      if (exposure == ErrorExposure.MASKED) {
        String ref = UUID.randomUUID().toString().substring(0, REFERENCE_LENGTH);
        boolean rejected = e instanceof McpTools.ToolError || e instanceof ToolArguments.Invalid;
        var event =
            rejected
                ? EventLog.warn(LOG, "mcp.tool.rejected")
                : EventLog.error(LOG, "mcp.tool.failed");
        event.detail("tool", definition.tool().name(), "ref", ref).cause(e).log();
        String message = (rejected ? "요청을 처리할 수 없습니다" : "내부 오류가 발생했습니다") + "(참조 " + ref + ")";
        if (e instanceof Error fatal) throw new MaskedToolError(message, fatal);
        return error(message);
      }
      if (e instanceof McpTools.ToolError
          || e instanceof McpTools.ToolFailure
          || e instanceof ToolArguments.Invalid) return error(e.getMessage());
      throw e;
    }
  }

  private static CallToolResult error(@Nullable String message) {
    return CallToolResult.builder()
        .isError(true)
        .content(List.of(new TextContent(message)))
        .build();
  }

  /** SDK가 Error 메시지도 직렬화하므로 원본은 cause 대신 suppressed에만 보존한다. */
  private static final class MaskedToolError extends Error {
    private MaskedToolError(String message, Error original) {
      super(message);
      addSuppressed(original);
    }
  }
}
