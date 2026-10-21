package io.scos.harness.testing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 하네스 자체 테스트용 가짜 MCP 서버(stdio JSON-RPC). 제품과 무관. 환경변수로 동작을 바꾼다: FAKE_IGNORE_EOF=1 → stdin EOF에도
 * 종료하지 않음(강제 종료 경로), FAKE_HANG=1 → 응답하지 않음(타임아웃 경로), FAKE_MISS_LOG=<path> → tools/call마다 미스 한 줄을
 * 쓰고도 정상 응답(fallback이 미스를 숨기는 상황의 모사), FAKE_RESPONSE=<json> → tools/call 응답 result 고정,
 * FAKE_CALL_ERROR=1 → tools/call에 JSON-RPC error(-32602) 응답. 기본 응답은 인자를 그대로 돌려주는 echo.
 *
 * <p>O2 비교 도구 시험용(n번째 tools/call에서, 프로세스마다 1부터 센다): FAKE_DIE_AFTER=n → 종료, FAKE_HANG_AFTER=n → 응답
 * 없음, FAKE_GARBAGE_AFTER=n → JSON이 아닌 줄, FAKE_TRUNCATED_AFTER=n → 잘린 JSON. FAKE_CALL_LOG=<path> →
 * tools/call마다 "FAKE_NAME\t질의" 한 줄, FAKE_ENV_LOG=<path> → 기동 때 받은 SCOS_QUERY_LOG(없으면
 * &lt;unset&gt;).
 */
public final class FakeMcpServer {
  public static void main(String[] args) throws Exception {
    ObjectMapper m = new ObjectMapper();
    BufferedReader in =
        new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
    String envLog = System.getenv("FAKE_ENV_LOG");
    if (envLog != null)
      Files.writeString(
          Path.of(envLog),
          "SCOS_QUERY_LOG="
              + (System.getenv().containsKey("SCOS_QUERY_LOG")
                  ? System.getenv("SCOS_QUERY_LOG")
                  : "<unset>")
              + "\n",
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    int toolCalls = 0;
    String line;
    while ((line = in.readLine()) != null) {
      JsonNode req = m.readTree(line);
      if (!req.hasNonNull("id")) continue;
      if (System.getenv("FAKE_HANG") != null) {
        Thread.sleep(3_600_000);
      }
      ObjectNode res = m.createObjectNode();
      res.put("jsonrpc", "2.0");
      res.set("id", req.get("id"));
      String method = req.get("method").asText();
      switch (method) {
        case "initialize" ->
            res.set(
                "result",
                m.createObjectNode()
                    .put("protocolVersion", "2025-06-18")
                    .set(
                        "serverInfo",
                        m.createObjectNode().put("name", "fake").put("version", "0")));
        case "tools/list" ->
            res.set("result", m.createObjectNode().set("tools", m.createArrayNode()));
        case "tools/call" -> {
          toolCalls++;
          String callLog = System.getenv("FAKE_CALL_LOG");
          if (callLog != null)
            Files.writeString(
                Path.of(callLog),
                System.getenv().getOrDefault("FAKE_NAME", "fake")
                    + "\t"
                    + req.path("params").path("arguments").path("query").asText()
                    + "\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
          if (at("FAKE_DIE_AFTER", toolCalls)) System.exit(1);
          if (at("FAKE_HANG_AFTER", toolCalls)) Thread.sleep(3_600_000);
          if (at("FAKE_GARBAGE_AFTER", toolCalls)) {
            out.println("warning: not json");
            continue;
          }
          if (at("FAKE_TRUNCATED_AFTER", toolCalls)) {
            out.println("{\"jsonrpc\":\"2.0\",\"id\":");
            continue;
          }
          if (System.getenv("FAKE_CALL_ERROR") != null) {
            res.set(
                "error",
                m.createObjectNode().put("code", -32602).put("message", "invalid params (fake)"));
            break;
          }
          String missLog = System.getenv("FAKE_MISS_LOG");
          if (missLog != null)
            Files.writeString(
                Path.of(missLog),
                "{\"kind\":\"embed\",\"sha256\":\"00\",\"text\":\"hidden\"}\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
          String fixed = System.getenv("FAKE_RESPONSE");
          JsonNode result =
              fixed != null
                  ? m.readTree(fixed)
                  : m.createObjectNode()
                      .set(
                          "content",
                          m.createArrayNode()
                              .add(
                                  m.createObjectNode()
                                      .put("type", "text")
                                      .put("text", m.writeValueAsString(req.get("params")))));
          res.set("result", result);
        }
        default ->
            res.set(
                "error",
                m.createObjectNode()
                    .put("code", -32601)
                    .put("message", "unknown method " + method));
      }
      out.println(m.writeValueAsString(res));
    }
    if (System.getenv("FAKE_IGNORE_EOF") != null) Thread.sleep(3_600_000);
  }

  /** 환경변수 name이 n이고 지금이 n번째 tools/call인가. */
  private static boolean at(String name, int calls) {
    String n = System.getenv(name);
    return n != null && Integer.parseInt(n) == calls;
  }
}
