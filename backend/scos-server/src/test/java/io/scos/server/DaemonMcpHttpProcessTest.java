package io.scos.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.scos.server.DaemonProcessScenario.Daemon;
import io.scos.server.DaemonProcessScenario.Settings;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Tag("db")
@Tag("process")
@Timeout(240)
@TestInstance(PER_CLASS)
@DisplayName("HTTP MCP를 켠 실제 데몬은 수집한 문서를 /mcp로 찾아 주고, 등록된 앞문이 낯선 요청을 막는다")
class DaemonMcpHttpProcessTest {
  static final String TOKEN = "p".repeat(McpHttpConfiguration.MIN_TOKEN_LENGTH);
  static final String QUERY = "배포";
  static final String FOREIGN_ORIGIN = "http://evil.example";
  static final String TOOLS_LIST = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";

  DaemonProcessScenario daemons;
  List<String> projected;
  List<String> found;
  int withoutToken;
  int withForeignOrigin;
  int exitCode;

  @BeforeAll
  void runDaemon(@TempDir Path dir) throws Exception {
    daemons = new DaemonProcessScenario(dir);
    Daemon daemon = daemons.start("a", Settings.defaults().mcpHttp(TOKEN));
    daemon.awaitReady();
    daemons.awaitProjectionRequestsDone();
    projected = daemons.projectedDocumentIds();
    found = searchDocumentIds(daemon.port());
    withoutToken = post(daemon.port(), Map.of());
    withForeignOrigin =
        post(daemon.port(), Map.of("Authorization", "Bearer " + TOKEN, "Origin", FOREIGN_ORIGIN));
    exitCode = daemon.sigterm();
  }

  @AfterAll
  void close() {
    if (daemons != null) daemons.close();
  }

  static List<String> searchDocumentIds(int port) {
    try (McpSyncClient client =
        McpClient.sync(
                HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port)
                    .endpoint(McpHttpConfiguration.PATH)
                    .requestBuilder(
                        HttpRequest.newBuilder().header("Authorization", "Bearer " + TOKEN))
                    .build())
            .build()) {
      client.initialize();
      return TestMcpHttpServer.documentIds(
          client.callTool(new CallToolRequest("search_context", Map.of("query", QUERY))));
    }
  }

  static int post(int port, Map<String, String> headers) throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + McpHttpConfiguration.PATH))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .POST(BodyPublishers.ofString(TOOLS_LIST));
    headers.forEach(request::header);
    try (HttpClient http = HttpClient.newHttpClient()) {
      return http.send(request.build(), BodyHandlers.discarding()).statusCode();
    }
  }

  @Test
  @DisplayName("세션은 /mcp로 투영된 문서를 찾는다")
  void findsProjectedDocuments() {
    // then
    assertThat(found).isNotEmpty().isSubsetOf(projected);
  }

  @Test
  @DisplayName("토큰 없는 요청은 401")
  void refusesWithoutToken() {
    // then
    assertThat(withoutToken).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
  }

  @Test
  @DisplayName("토큰이 맞아도 외부 Origin이면 403")
  void refusesForeignOrigin() {
    // then
    assertThat(withForeignOrigin).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
  }

  @Test
  @DisplayName("HTTP MCP를 켠 채로도 SIGTERM에 정상 종료한다")
  void stopsCleanly() {
    // then
    assertThat(exitCode).isEqualTo(DaemonProcessScenario.SIGTERM_EXIT);
  }
}
