package io.scos.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * bootJar `mcp` 자식 프로세스와 줄 단위 JSON-RPC로 대화하는 테스트 컨텍스트. stderr는 파일로 받는다. 서버가 답하지 않으면 readLine이 영원히
 * 막히므로, 상한이 지나면 프로세스를 죽여 stdout을 닫는다 — 그러면 {@link #closeStdin}이 given 준비 실패로 멈춘다.
 */
final class McpStdioSession {
  /** stdin을 닫은 뒤까지 본 것: stdout 줄 전부, 종료 코드, stderr 전문. */
  record Transcript(List<String> stdout, int exitCode, String stderr) {}

  private static final String INITIALIZE =
      "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"0\"}}}";
  private static final String INITIALIZED =
      "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}";

  private final Process process;
  private final Duration limit;
  private final Path stderr;
  private final CompletableFuture<Void> watchdog;
  private final OutputStream in;
  private final BufferedReader out;
  private final List<String> stdout = new ArrayList<>();
  private int nextId = 2;

  private McpStdioSession(Process process, Duration limit, Path stderr) {
    this.process = process;
    this.limit = limit;
    this.stderr = stderr;
    this.watchdog =
        CompletableFuture.runAsync(
            process::destroyForcibly,
            CompletableFuture.delayedExecutor(limit.toSeconds(), TimeUnit.SECONDS));
    this.in = process.getOutputStream();
    this.out =
        new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
  }

  /** 이 jar의 `mcp`를 이 환경 변수로 띄운다(JVM은 scos.java.home, 없으면 테스트 JVM의 것). */
  static McpStdioSession launch(Path jar, Map<String, String> env, Path stderr, Duration limit)
      throws IOException {
    String javaBin =
        System.getProperty("scos.java.home", System.getProperty("java.home")) + "/bin/java";
    ProcessBuilder pb =
        new ProcessBuilder(javaBin, "-jar", jar.toString(), "mcp").redirectError(stderr.toFile());
    pb.environment().putAll(env);
    return new McpStdioSession(pb.start(), limit, stderr);
  }

  /** initialize를 보내 응답 한 줄을 받고 initialized 알림을 보낸다. */
  void initialize() throws IOException {
    send(INITIALIZE);
    stdout.add(out.readLine());
    send(INITIALIZED);
  }

  /** tools/call 하나를 보내 응답 한 줄을 받는다. arguments는 JSON 객체 글. */
  void callTool(String name, String argumentsJson) throws IOException {
    send(
        "{\"jsonrpc\":\"2.0\",\"id\":"
            + nextId++
            + ",\"method\":\"tools/call\",\"params\":{\"name\":\""
            + name
            + "\",\"arguments\":"
            + argumentsJson
            + "}}");
    stdout.add(out.readLine());
  }

  /** stdin을 닫고 stdout이 닫힐 때까지 읽은 뒤 종료를 기다린다. 상한 안에 끝나지 않으면 given 준비 실패. */
  Transcript closeStdin() throws IOException, InterruptedException {
    try (in;
        out) {
      in.close();
      for (String line = out.readLine(); line != null; line = out.readLine()) stdout.add(line);
    }
    if (!watchdog.cancel(false))
      throw new IllegalStateException("given 준비 실패: MCP 프로세스가 " + limit + " 안에 답하고 stdout을 닫아야 한다");
    if (!process.waitFor(limit.toSeconds(), TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new IllegalStateException("given 준비 실패: MCP 프로세스가 stdin EOF 뒤 끝나야 한다");
    }
    return new Transcript(List.copyOf(stdout), process.exitValue(), Files.readString(stderr));
  }

  private void send(String message) throws IOException {
    in.write((message + "\n").getBytes(StandardCharsets.UTF_8));
    in.flush();
  }
}
