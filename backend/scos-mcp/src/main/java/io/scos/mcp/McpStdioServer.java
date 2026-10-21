package io.scos.mcp;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.function.Function;

/**
 * stdio MCP 서버(ADR-0010 M2: SDK 직접). 입력 스키마는 TS tools/list 녹화본(옛
 * experiments/pa/results/ts-mcp-wire.json, f93f5966 이전 이력)과 같은 JSON을 상수로 둔다. 핸들러의 계약 위반은
 * isError:true + 메시지로(TS와 같은 와이어), 인프라 오류만 예외로 전파한다. stdout은 SDK 전송만 쓴다.
 */
public final class McpStdioServer {
  static final String SERVER_NAME = "scos-mcp-server";
  static final String SERVER_VERSION = "0.0.1";
  private static final String INT_RANGE =
      "\"minimum\":-9007199254740991,\"maximum\":9007199254740991";
  static final String SEARCH_SCHEMA =
      "{\"$schema\":\"http://json-schema.org/draft-07/schema#\",\"type\":\"object\",\"properties\":{"
          + "\"query\":{\"type\":\"string\",\"minLength\":1,\"description\":\"검색할 질문 또는 키워드\"},"
          + "\"limit\":{\"description\":\"반환할 결과 수 (기본 5, 최대 10으로 제한됨)\",\"type\":\"integer\","
          + INT_RANGE
          + "}},\"required\":[\"query\"]}";
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
          + "}},\"required\":[\"query\"]}";
  static final String ASSEMBLE_DESCRIPTION =
      "질의로 예산 내 최적 컨텍스트 묶음을 한 번에 조립합니다. search_context→read_document "
          + "왕복 없이, 검색·랭킹·토큰 예산 배치를 한 호출로 마쳐 제목·출처·문서 id·본문이 담긴 유닛 목록을 반환합니다. "
          + "freshness.stale=true면 데이터가 12시간 이상 묵었음을 알리고, latestCapturedAt=null은 최신성 확인 불가로 해석하세요. "
          + "tokenBudget은 기본 2000이며 500~8000 범위로 자동 조정됩니다.";

  private final McpTools tools;
  private final CountDownLatch stdinEof = new CountDownLatch(1);
  private McpSyncServer server;

  public McpStdioServer(McpTools tools) {
    this.tools = tools;
  }

  /**
   * 서버를 stdio에 연결한다. stdin EOF는 {@link #awaitStdinEof()}로 알린다 — Spring 컨텍스트가 살아 있으면 SDK 스레드만으로는
   * JVM이 끝나지 않아(첫 하네스 실행에서 강제 종료로 확인) 조립 루트가 EOF 뒤 명시적으로 종료한다.
   */
  public synchronized void start() {
    if (server != null) throw new IllegalStateException("already started");
    StdioServerTransportProvider transport =
        new StdioServerTransportProvider(
            McpJsonDefaults.getMapper(), new EofSignalling(System.in, stdinEof), System.out);
    server =
        McpServer.sync(transport)
            .serverInfo(SERVER_NAME, SERVER_VERSION)
            .capabilities(ServerCapabilities.builder().tools(true).build())
            .tools(
                tool("search_context", SEARCH_DESCRIPTION, SEARCH_SCHEMA, tools::searchContext),
                tool("read_document", READ_DESCRIPTION, READ_SCHEMA, tools::readDocument),
                tool(
                    "related_documents",
                    RELATED_DESCRIPTION,
                    RELATED_SCHEMA,
                    tools::relatedDocuments),
                tool(
                    "assemble_context",
                    ASSEMBLE_DESCRIPTION,
                    ASSEMBLE_SCHEMA,
                    tools::assembleContext))
            .build();
  }

  /** stdin이 닫힐 때까지 기다린다(= 클라이언트 종료 신호). */
  public void awaitStdinEof() throws InterruptedException {
    stdinEof.await();
  }

  /** read가 -1을 돌려주는 순간 래치를 내린다. SDK가 어떤 Reader로 감싸도 EOF는 여기서 보인다. */
  private static final class EofSignalling extends FilterInputStream {
    private final CountDownLatch eof;

    EofSignalling(InputStream in, CountDownLatch eof) {
      super(in);
      this.eof = eof;
    }

    @Override
    public int read() throws IOException {
      int b = super.read();
      if (b < 0) eof.countDown();
      return b;
    }

    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
      int n = super.read(buf, off, len);
      if (n < 0) eof.countDown();
      return n;
    }
  }

  public synchronized void stop() {
    if (server != null) {
      server.close();
      server = null;
    }
  }

  private static SyncToolSpecification tool(
      String name,
      String description,
      String schema,
      Function<Map<String, Object>, String> handler) {
    return SyncToolSpecification.builder()
        .tool(
            Tool.builder()
                .name(name)
                .description(description)
                .inputSchema(McpJsonDefaults.getMapper(), schema)
                .build())
        .callHandler(
            (exchange, request) -> {
              try {
                return CallToolResult.builder()
                    .content(List.of(new TextContent(handler.apply(request.arguments()))))
                    .build();
              } catch (McpTools.ToolError | ToolArguments.Invalid e) {
                return CallToolResult.builder()
                    .isError(true)
                    .content(List.of(new TextContent(e.getMessage())))
                    .build();
              }
            })
        .build();
  }

  static final String SEARCH_DESCRIPTION =
      "팀 Slack 채널, Notion 워크스페이스, Jira 프로젝트, Google Drive 문서 지식에서 의미 기반 검색으로 관련 문서 조각을 찾습니다. "
          + "질문이나 키워드로 질의하면 제목·발췌·출처·문서 id·점수가 담긴 상위 결과를 반환합니다. "
          + "freshness.stale=true면 데이터가 12시간 이상 묵었음을 알리고, latestCapturedAt=null은 최신성 확인 불가로 해석하세요. "
          + "특정 문서의 전체 내용이 필요하면 반환된 documentId로 read_document를 호출하세요.";
  static final String READ_DESCRIPTION =
      "search_context 결과의 documentId로 문서를 페이지 단위로 읽습니다. nextOffset이 null이 아니면 그 값을 offset으로 넘겨 다음 페이지를 읽으세요.";
  static final String RELATED_DESCRIPTION =
      "질의 없이 특정 문서와 관련된 다른 Slack 스레드·Notion 문서를 찾습니다. "
          + "같은 주제를 다룬 과거 논의, 유사 장애 사례, Slack↔Notion 짝 문서를 찾을 때 유용합니다. documentId는 search_context 또는 read_document 결과에서 얻습니다.";
}
