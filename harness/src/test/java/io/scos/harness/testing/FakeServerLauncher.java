package io.scos.harness.testing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.scos.harness.fixture.FixtureSpec;
import io.scos.harness.mcp.JsonRpcStdioClient;
import io.scos.harness.server.ServerLauncher;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** FakeMcpServer를 같은 JVM 클래스패스로 띄우는 런처 — 하네스 자체 테스트 전용. */
public final class FakeServerLauncher implements ServerLauncher {
  private final Map<String, String> extraEnv;

  public FakeServerLauncher(Map<String, String> extraEnv) {
    this.extraEnv = extraEnv;
  }

  public static JsonRpcStdioClient.Launch launch(Path cwd, Map<String, String> extraEnv) {
    String java =
        Path.of(
                System.getProperty("harness.java.home", System.getProperty("java.home")),
                "bin",
                "java")
            .toString();
    Map<String, String> env = new HashMap<>(extraEnv);
    env.put("PATH", System.getenv().getOrDefault("PATH", "/usr/bin:/bin"));
    return new JsonRpcStdioClient.Launch(
        List.of(java, "-cp", System.getProperty("java.class.path"), FakeMcpServer.class.getName()),
        env,
        cwd,
        cwd.resolve("fake-stderr.log"));
  }

  /**
   * 가짜 서버 실행 파일 하나(--server-command처럼 서버를 실행 파일로 받는 명령의 시험용). JVM이 아니라 bash 가짜
   * 서버(fake-mcp-server.sh — FAKE_RESPONSE·FAKE_DIE_AFTER·FAKE_ENV_LOG만)라 기동이 밀리초다: 명령의 시간 상한이 부하 아래
   * JVM 기동 시간을 재지 않게 한다. env는 스크립트 안에서 내보낸다 — 실행 파일을 부르는 쪽 환경(예: SCOS_QUERY_LOG)은 그대로 물려받는다.
   */
  public static Path script(Path dir, String name, Map<String, String> env) throws IOException {
    StringBuilder body = new StringBuilder("#!/bin/bash\n");
    env.forEach(
        (k, v) -> body.append("export ").append(k).append('=').append(quote(v)).append('\n'));
    try (InputStream server = FakeServerLauncher.class.getResourceAsStream("fake-mcp-server.sh")) {
      body.append(new String(server.readAllBytes(), StandardCharsets.UTF_8));
    }
    Path script = Files.writeString(dir.resolve(name + ".sh"), body);
    Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
    return script;
  }

  /** search_context 응답 result: 문서 id마다 hit 하나(TS·Java 같은 계약의 content[0].text JSON). */
  public static String searchResult(String... documentIds) throws IOException {
    ObjectMapper m = JsonRpcStdioClient.mapper();
    ObjectNode body = m.createObjectNode();
    ArrayNode hits = body.putArray("hits");
    for (String id : documentIds) hits.addObject().put("documentId", id).put("score", 1);
    ObjectNode result = m.createObjectNode();
    result
        .putArray("content")
        .addObject()
        .put("type", "text")
        .put("text", m.writeValueAsString(body));
    return m.writeValueAsString(result);
  }

  private static String quote(String value) {
    return "'" + value.replace("'", "'\\''") + "'";
  }

  @Override
  public String implementationId() {
    return "fake";
  }

  @Override
  public Path prepare(FixtureSpec fixture, Path workDir) {
    return workDir.resolve("no-store");
  }

  @Override
  public JsonRpcStdioClient.Launch launch(
      FixtureSpec fixture, Path workDir, Path store, HarnessEnv env) {
    Map<String, String> e = new HashMap<>(extraEnv);
    if (e.containsKey("FAKE_MISS_LOG")) e.put("FAKE_MISS_LOG", env.missLog().toString());
    return launch(workDir, e);
  }
}
