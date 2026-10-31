package io.scos.mcp;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import org.jspecify.annotations.Nullable;

/** stdio MCP 서버(ADR-0010 M2: SDK 직접). 도구 명세는 {@link McpToolSpecs}. stdout은 SDK 전송만 쓴다. */
public final class McpStdioServer {
  /** stdin EOF 뒤 처리 중인 응답을 기다리는 상한 — 넘기면 버리고 닫는다(hang 방지). 도구 호출(임베딩 API 포함)보다 넉넉하게. */
  static final Duration DRAIN_LIMIT = Duration.ofSeconds(60);

  private final McpTools tools;
  private final McpServerProfile profile;
  private final InputStream stdin;
  private final OutputStream stdout;
  private final CountDownLatch stdinEof = new CountDownLatch(1);
  private @Nullable McpSyncServer server;

  public McpStdioServer(McpTools tools) {
    this(tools, McpServerProfile.ORGANIZATION, System.in, System.out);
  }

  /** 프로필과 stdin·stdout을 조립 루트가 준다(단독 클라이언트 scos-local은 테스트에서 파이프를 넘긴다). */
  public McpStdioServer(
      McpTools tools, McpServerProfile profile, InputStream stdin, OutputStream stdout) {
    this(tools, profile, stdin, stdout, DRAIN_LIMIT);
  }

  McpStdioServer(
      McpTools tools,
      McpServerProfile profile,
      InputStream stdin,
      OutputStream stdout,
      Duration drainLimit) {
    this.tools = tools;
    this.profile = profile;
    InFlightRequests inFlight = new InFlightRequests(drainLimit, stdinEof);
    this.stdin = inFlight.reading(stdin);
    this.stdout = inFlight.writing(stdout);
  }

  /**
   * 서버를 stdio에 연결한다. stdin EOF는 {@link #awaitStdinEof()}로 알린다 — Spring 컨텍스트가 살아 있으면 SDK 스레드만으로는
   * JVM이 끝나지 않아(첫 하네스 실행에서 강제 종료로 확인) 조립 루트가 EOF 뒤 명시적으로 종료한다.
   */
  public synchronized void start() {
    if (server != null) throw new IllegalStateException("already started");
    StdioServerTransportProvider transport =
        new StdioServerTransportProvider(McpJsonDefaults.getMapper(), stdin, stdout);
    server =
        McpServer.sync(transport)
            .serverInfo(profile.name(), profile.version())
            .instructions(profile.instructions())
            .capabilities(McpToolSpecs.capabilities())
            .tools(McpToolSpecs.sync(tools, profile))
            .build();
  }

  /** stdin이 닫히고 그때까지 읽은 요청에 모두 답할 때까지(최대 {@link #DRAIN_LIMIT}) 기다린다(= 클라이언트 종료 신호). */
  public void awaitStdinEof() throws InterruptedException {
    stdinEof.await();
  }

  public synchronized void stop() {
    if (server != null) {
      server.close();
      server = null;
    }
  }
}
