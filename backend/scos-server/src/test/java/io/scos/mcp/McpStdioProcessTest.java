package io.scos.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.collection.internal.postgres.PostgresSupport;
import io.scos.support.FakeHttpServer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * MCP stdio 실제 프로세스(bootJar `mcp`, ADR-0010·ADR-0023): stdout은 JSON-RPC 전용이다 — 로그는 stderr로만 가고, 질의
 * 로그는 SCOS_QUERY_LOG 파일로만 간다. initialize → search_context 한 번을 보낸 뒤 stdin을 닫아 끝낸다. 임베딩은 가짜 Gemini가
 * 답한다.
 */
@Tag("db")
@Tag("process")
@DisplayName("MCP stdio 서버의 stdout은 JSON-RPC 전용이다(ADR-0010·ADR-0023)")
class McpStdioProcessTest {
  static final Path JAR =
      Path.of(System.getProperty("scos.boot.jar", "build/libs/scos-backend.jar"));

  /** 기동부터 종료까지의 상한 — 넘기면 프로세스를 죽여 막힌 줄 읽기를 끝낸다. */
  static final Duration PROCESS_LIMIT = Duration.ofMinutes(2);

  static final ObjectMapper JSON = new ObjectMapper();
  static final String EMBED = "/v1beta/models/gemini-embedding-001:batchEmbedContents";
  static final int EMBEDDING_DIM = 768;
  static final String QUERY = "정산 규정";

  static String vectors(int n) {
    StringBuilder sb = new StringBuilder("{\"embeddings\":[");
    for (int i = 0; i < n; i++) {
      sb.append(i > 0 ? "," : "").append("{\"values\":[");
      for (int d = 0; d < EMBEDDING_DIM; d++) sb.append(d > 0 ? "," : "").append("0.1");
      sb.append("]}");
    }
    return sb.append("]}").toString();
  }

  @Nested
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  @DisplayName("initialize 뒤 search_context를 한 번 보내고 stdin을 닫으면")
  class SearchOnceThenEof {
    FakeHttpServer gemini;
    List<String> stdout;
    String stderr;
    List<String> queryLog;
    int exitCode;

    @BeforeAll
    void searchOnce(@TempDir Path dir) throws Exception {
      PostgresSupport.truncateAll();
      gemini = new FakeHttpServer();
      gemini.on(
          "POST",
          EMBED,
          r -> FakeHttpServer.ok(vectors(JSON.readTree(r.body()).get("requests").size())));
      Path queries = dir.resolve("mcp-queries.jsonl");
      McpStdioSession session =
          McpStdioSession.launch(
              JAR, environment(queries), dir.resolve("stderr.log"), PROCESS_LIMIT);
      session.initialize();
      session.callTool("search_context", "{\"query\":\"" + QUERY + "\"}");
      McpStdioSession.Transcript transcript = session.closeStdin();
      stdout = transcript.stdout();
      exitCode = transcript.exitCode();
      stderr = transcript.stderr();
      queryLog = Files.exists(queries) ? Files.readAllLines(queries) : List.of();
    }

    /** 테스트 DB, 가짜 Gemini, 재정렬 없음, 질의 로그 파일, 터미널에 붙은 것으로 본다(detect라면 색을 켠다). */
    Map<String, String> environment(Path queries) {
      return Map.of(
          "SCOS_DB_URL", PostgresSupport.jdbcUrl(),
          "SCOS_DB_USER", PostgresSupport.username(),
          "SCOS_DB_PASSWORD", PostgresSupport.password(),
          "GEMINI_API_KEY", "unused-in-test",
          "SCOS_GEMINI_BASE_URL", gemini.baseUrl(),
          "SCOS_RERANK_PROVIDER", "none",
          "SCOS_QUERY_LOG", queries.toString(),
          "SPRING_OUTPUT_ANSI_CONSOLE_AVAILABLE", "true");
    }

    @AfterAll
    void close() {
      try {
        gemini.assertNoUnmatched();
      } finally {
        gemini.close();
      }
    }

    @Test
    @DisplayName("stdout의 줄은 모두 JSON-RPC 메시지다(로그가 섞이지 않는다)")
    void stdoutCarriesOnlyJsonRpc() {
      // then
      assertThat(stdout)
          .isNotEmpty()
          .allSatisfy(
              line -> assertThat(JSON.readTree(line).get("jsonrpc").asString()).isEqualTo("2.0"));
    }

    @Test
    @DisplayName("검색 응답은 오류가 아니다")
    void searchSucceeds() {
      // when
      JsonNode response = JSON.readTree(stdout.get(1));

      // then
      assertThat(response.get("result").get("isError").asBoolean(false)).isFalse();
    }

    @Test
    @DisplayName("질의 로그 파일에 그 질의 한 줄이 남는다")
    void writesQueryLog() {
      // then
      assertThat(queryLog)
          .singleElement()
          .satisfies(
              line -> assertThat(JSON.readTree(line).get("query").asString()).isEqualTo(QUERY));
    }

    @Test
    @DisplayName("기동 로그는 stderr로 간다")
    void logsGoToStderr() {
      // then
      assertThat(stderr).contains("MCP 서버 시작");
    }

    @Test
    @DisplayName("stderr 로그 줄은 Spring Boot 기본 콘솔 모양이다")
    void stderrUsesSpringBootShape() {
      // then
      assertThat(stderr)
          .containsPattern(
              "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}(?:Z|[+-]\\d{2}:\\d{2})  INFO \\d+ --- "
                  + "\\[scos\\] \\[.{15}\\] .{40} : MCP 서버 시작");
    }

    @Test
    @DisplayName("터미널에 붙어도 stderr 로그에 색 코드를 쓰지 않는다")
    void stderrHasNoColor() {
      // then
      assertThat(stderr).doesNotContain("\u001B[");
    }

    @Test
    @DisplayName("stdin을 닫으면 종료 코드 0으로 끝난다")
    void exitsCleanly() {
      // then
      assertThat(exitCode).isZero();
    }
  }
}
