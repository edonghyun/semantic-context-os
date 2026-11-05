package io.scos.server;

import io.scos.mcp.McpStdioServer;
import java.util.Optional;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

/** 첫 인자로 고르는 실행 방식: 프로필, 웹 여부, 컨텍스트를 언제까지 살려 둘지. */
enum LaunchMode {
  /** 한 번 돌고 끝나는 명령. */
  COMMAND(Optional.empty(), WebApplicationType.NONE),
  ACCESS_EXPLAIN(Optional.of("access-explain"), WebApplicationType.NONE),
  /** 프로필 mcp(읽기 전용 연결·Flyway validate만·stdio 수명주기). 서버 시작은 SmartLifecycle이 한다. */
  MCP(Optional.of("mcp"), WebApplicationType.NONE),
  /** 프로필 daemon(감독 루프). 웹(Actuator HTTP)은 이 방식만 연다(ADR-0020 §3). */
  DAEMON(Optional.of("daemon"), WebApplicationType.SERVLET);

  private final Optional<String> profile;
  private final WebApplicationType webType;

  LaunchMode(Optional<String> profile, WebApplicationType webType) {
    this.profile = profile;
    this.webType = webType;
  }

  static LaunchMode of(String[] args) {
    if (args.length == 0) return COMMAND;
    if (args.length >= 3
        && args[0].equals("source")
        && args[1].equals("access")
        && args[2].equals("explain")) return ACCESS_EXPLAIN;
    return switch (args[0]) {
      case "mcp" -> MCP;
      case "daemon" -> DAEMON;
      default -> COMMAND;
    };
  }

  /**
   * 웹 여부는 여기서 정한다 — yml의 spring.main.web-application-type은 환경 바인딩이 이 값을 덮어쓰므로 쓰지 않는다.
   * SpringApplicationBuilder로 띄우는 테스트는 .web(NONE)을 직접 준다.
   */
  void configure(SpringApplication app) {
    profile.ifPresent(app::setAdditionalProfiles);
    app.setWebApplicationType(webType);
  }

  /**
   * 끝날 때까지 기다린다. 컨텍스트가 아직 살아 있어 종료 코드를 물어도 되면 true.
   *
   * <ul>
   *   <li>daemon: 루프가 끝날 때까지(SIGTERM → 종료 훅 → SmartLifecycle.stop). SIGTERM이면 종료 훅이 컨텍스트를 닫는 중이라 종료
   *       코드는 JVM이 정한다(143) — 닫힌 컨텍스트에 exit를 묻지 않는다.
   *   <li>mcp: stdin EOF(클라이언트 종료)까지 기다렸다가 닫는다. 먼저 끝내면 initialize 전에 종료되고, 기다리지 않으면 Spring 컨텍스트 때문에
   *       EOF 뒤에도 JVM이 남는다. EOF 알림은 읽은 요청에 다 답한 뒤라(McpStdioServer) 요청 직후 stdin을 닫은 클라이언트도 응답을 받는다.
   * </ul>
   */
  boolean awaitEnd(ConfigurableApplicationContext context) {
    try {
      switch (this) {
        case DAEMON -> context.getBean(CollectionDaemon.class).awaitTermination();
        case MCP -> context.getBean(McpStdioServer.class).awaitStdinEof();
        case COMMAND, ACCESS_EXPLAIN -> {}
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    return this != DAEMON || context.isActive();
  }
}
