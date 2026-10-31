package io.scos.server;

import static io.scos.spi.store.ContextUnits.unit;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.scos.boot.AccessProperties;
import io.scos.engine.ContextEngine;
import io.scos.mcp.ErrorExposure;
import io.scos.mcp.McpTools;
import io.scos.mcp.QueryLog;
import io.scos.spi.embedding.FixedEmbedder;
import io.scos.spi.store.ContextUnit;
import io.scos.spi.store.IndexedText;
import io.scos.spi.store.SpaceId;
import io.scos.store.memory.MemoryKeywordIndex;
import io.scos.store.memory.MemoryUnitMaintenance;
import io.scos.store.memory.MemoryUnitStore;
import io.scos.store.memory.MemoryVectorIndex;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 데몬 HTTP MCP 엔드포인트를 내장 Tomcat에 올린 테스트 컨텍스트(메모리 저장소, 문서 하나). 앞문 필터는 붙이지 않는다 — 인증은
 * LocalBearerFilterTest와 실제 데몬 프로세스 테스트가 맡는다.
 */
final class TestMcpHttpServer implements AutoCloseable {
  private static final Duration REQUEST_LIMIT = Duration.ofSeconds(5);
  static final SpaceId SPACE = SpaceId.LEGACY;
  static final String STORED_DOCUMENT = "deploy-guide";
  static final String STORED_TITLE = "배포 절차";
  static final AccessProperties SINGLE_USER = new AccessProperties(null, null, null, null);

  private static final ObjectMapper JSON = new ObjectMapper();

  private final MemoryUnitStore units = new MemoryUnitStore();
  private final MemoryVectorIndex vectors = new MemoryVectorIndex();
  private final MemoryUnitMaintenance maintenance = new MemoryUnitMaintenance(units, vectors);
  private final MemoryKeywordIndex keywords = new MemoryKeywordIndex();
  private final Tomcat tomcat = new Tomcat();
  private final McpHttpEndpoint endpoint;
  private final int port;

  private TestMcpHttpServer(Path baseDir) throws Exception {
    store(
        unit("u-1")
            .inDocument(STORED_DOCUMENT)
            .titled(STORED_TITLE)
            .content("배포 절차는 승인 뒤 진행한다")
            .build());
    ContextEngine engine =
        ContextEngine.builder()
            .units(units)
            .unrestricted(maintenance)
            .keywords(keywords)
            .vectors(vectors)
            .embedder(FixedEmbedder.zero(2))
            .defaultSpace(SPACE)
            .build();
    endpoint =
        McpHttpEndpoint.create(
            new McpTools(engine, JsonMapper.builder().build(), QueryLog.NONE),
            McpHttpConfiguration.PATH,
            ErrorExposure.RAW,
            McpHttpConfiguration.contextExtractor(SINGLE_USER),
            () -> {});
    tomcat.setPort(0);
    tomcat.setBaseDir(baseDir.toString());
    Context ctx = tomcat.addContext("", null);
    Tomcat.addServlet(ctx, "mcp", endpoint.servlet()).setAsyncSupported(true);
    ctx.addServletMappingDecoded(McpHttpConfiguration.PATH, "mcp");
    tomcat.getConnector();
    tomcat.start();
    port = tomcat.getConnector().getLocalPort();
  }

  static TestMcpHttpServer start(Path baseDir) throws Exception {
    return new TestMcpHttpServer(baseDir);
  }

  private void store(ContextUnit u) {
    maintenance.upsert(SPACE, List.of(u));
    keywords.upsert(
        SPACE,
        List.of(
            new IndexedText(u.id(), u.documentId(), u.title(), u.content(), u.partition(), false)));
  }

  /** MCP 클라이언트로 HTTP 왕복 한 번(initialize 포함). */
  CallToolResult call(String tool, Map<String, Object> arguments) {
    try (McpSyncClient client = client()) {
      client.initialize();
      return client.callTool(new CallToolRequest(tool, arguments));
    }
  }

  /** MCP 클라이언트로 initialize만 하고 서버가 실은 instructions를 돌려준다(없으면 null). */
  String instructions() {
    try (McpSyncClient client = client()) {
      return client.initialize().instructions();
    }
  }

  private McpSyncClient client() {
    return McpClient.sync(
            HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port)
                .endpoint(McpHttpConfiguration.PATH)
                .build())
        .requestTimeout(REQUEST_LIMIT)
        .initializationTimeout(REQUEST_LIMIT)
        .build();
  }

  /** search_context 결과의 문서 id들. */
  List<String> searchDocumentIds(String query) {
    return documentIds(call("search_context", Map.of("query", query)));
  }

  /** search_context 응답 본문의 hits에서 문서 id를 순서대로 뽑는다. */
  static List<String> documentIds(CallToolResult result) {
    JsonNode body = JSON.readTree(((TextContent) result.content().getFirst()).text());
    return body.get("hits").valueStream().map(h -> h.get("documentId").asString()).toList();
  }

  @Override
  public void close() throws Exception {
    tomcat.stop();
    tomcat.destroy();
    endpoint.close();
  }
}
