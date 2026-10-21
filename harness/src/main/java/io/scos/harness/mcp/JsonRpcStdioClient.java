package io.scos.harness.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * MCP stdio 클라이언트 — 원시 JSON-RPC(줄 단위 JSON)만 쓴다. 프로세스 시작·readiness(initialize 응답)·종료(stdin EOF → 대기
 * → 강제 종료)·재시작을 소유한다. 제품 클래스는 모른다. 응답 대기는 시간 상한이 있고 sleep·retry로 숨기지 않는다.
 */
public final class JsonRpcStdioClient implements AutoCloseable {
  public record Launch(List<String> command, Map<String, String> env, Path cwd, Path stderrLog) {}

  public static final class ProtocolException extends RuntimeException {
    public ProtocolException(String m) {
      super(m);
    }
  }

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final Launch launch;
  private final Duration timeout;
  private final ExecutorService reader =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "mcp-stdout");
            t.setDaemon(true);
            return t;
          });
  private Process process;
  private BufferedWriter stdin;
  private BufferedReader stdout;
  private int nextId = 1;
  private JsonNode initializeResult;

  public JsonRpcStdioClient(Launch launch, Duration timeout) {
    this.launch = launch;
    this.timeout = timeout;
  }

  public static ObjectMapper mapper() {
    return MAPPER;
  }

  /** 프로세스를 띄우고 initialize 핸드셰이크가 끝나야 반환한다(= readiness). 시간 안에 못 끝나면 죽이고 예외. */
  public JsonNode start() throws IOException {
    if (process != null) throw new IllegalStateException("already started");
    ProcessBuilder pb = new ProcessBuilder(launch.command()).directory(launch.cwd().toFile());
    pb.environment().clear();
    pb.environment().putAll(launch.env());
    pb.redirectError(
        launch.stderrLog() == null
            ? ProcessBuilder.Redirect.DISCARD
            : ProcessBuilder.Redirect.appendTo(launch.stderrLog().toFile()));
    process = pb.start();
    stdin =
        new BufferedWriter(
            new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
    stdout =
        new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
    try {
      ObjectNode params = MAPPER.createObjectNode();
      params.put("protocolVersion", "2025-06-18");
      params.set("capabilities", MAPPER.createObjectNode());
      params.set(
          "clientInfo",
          MAPPER.createObjectNode().put("name", "scos-harness").put("version", "0.1"));
      initializeResult = request("initialize", params);
      ObjectNode initialized = MAPPER.createObjectNode();
      initialized.put("jsonrpc", "2.0");
      initialized.put("method", "notifications/initialized");
      send(initialized);
      return initializeResult;
    } catch (RuntimeException | IOException e) {
      kill();
      throw e;
    }
  }

  public JsonNode initializeResult() {
    return initializeResult;
  }

  public boolean isAlive() {
    return process != null && process.isAlive();
  }

  public JsonNode listTools() throws IOException {
    return request("tools/list", MAPPER.createObjectNode());
  }

  /** tools/call 응답의 result(또는 JSON-RPC error 객체를 {"jsonrpcError": …}로). 응답 본문은 가공하지 않는다. */
  public JsonNode callTool(String name, JsonNode arguments) throws IOException {
    ObjectNode params = MAPPER.createObjectNode();
    params.put("name", name);
    params.set("arguments", arguments);
    return request("tools/call", params);
  }

  private JsonNode request(String method, ObjectNode params) throws IOException {
    int id = nextId++;
    ObjectNode req = MAPPER.createObjectNode();
    req.put("jsonrpc", "2.0");
    req.put("id", id);
    req.put("method", method);
    req.set("params", params);
    send(req);
    while (true) {
      JsonNode msg = readLine();
      if (msg == null)
        throw new ProtocolException(
            "server closed stdout before responding to "
                + method
                + " (exit="
                + exitCodeOrNull()
                + ")");
      if (!msg.hasNonNull("id")) continue; // 서버 알림은 무시(기록하지 않음)
      if (msg.get("id").asInt() != id)
        throw new ProtocolException(
            "unexpected response id " + msg.get("id") + " for request " + id);
      if (msg.has("error")) {
        ObjectNode wrapped = MAPPER.createObjectNode();
        wrapped.set("jsonrpcError", msg.get("error"));
        return wrapped;
      }
      return msg.get("result");
    }
  }

  private void send(ObjectNode msg) throws IOException {
    stdin.write(MAPPER.writeValueAsString(msg));
    stdin.write('\n');
    stdin.flush();
  }

  private JsonNode readLine() throws IOException {
    Future<String> f = reader.submit(stdout::readLine);
    try {
      String line = f.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
      return line == null ? null : MAPPER.readTree(line);
    } catch (TimeoutException e) {
      f.cancel(true);
      kill();
      throw new ProtocolException("no response within " + timeout + " — process killed");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException(e);
    } catch (ExecutionException e) {
      throw new IOException(e.getCause());
    }
  }

  private Integer exitCodeOrNull() {
    return process != null && !process.isAlive() ? process.exitValue() : null;
  }

  /** 정상 종료: stdin EOF → timeout 안에 스스로 끝나야 한다. 안 끝나면 강제 종료하고 그 사실을 돌려준다. */
  public Shutdown stop() {
    if (process == null) return new Shutdown(null, false);
    try {
      stdin.close();
    } catch (IOException ignored) {
      // 이미 죽은 프로세스
    }
    boolean exited;
    try {
      exited = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      exited = false;
    }
    boolean forced = false;
    if (!exited) {
      forced = true;
      kill();
    }
    Shutdown s = new Shutdown(process.exitValue(), forced);
    process = null;
    initializeResult = null;
    return s;
  }

  public record Shutdown(Integer exitCode, boolean forced) {}

  private void kill() {
    if (process != null && process.isAlive()) {
      // exec하지 않는 래퍼(셸 → node)의 자손도 끈다(F11 리뷰 m6)
      process.descendants().forEach(ProcessHandle::destroyForcibly);
      process.destroyForcibly();
      try {
        process.waitFor(5, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  @Override
  public void close() {
    stop();
    reader.shutdownNow();
  }

  /** 테스트·진단용: stderr 로그 꼬리(비밀·질의 본문이 섞일 수 있어 호출자가 출력 여부를 정한다). */
  public static List<String> tail(Path log, int lines) throws IOException {
    if (log == null || !Files.exists(log)) return List.of();
    List<String> all = Files.readAllLines(log, StandardCharsets.UTF_8);
    return new ArrayList<>(all.subList(Math.max(0, all.size() - lines), all.size()));
  }
}
