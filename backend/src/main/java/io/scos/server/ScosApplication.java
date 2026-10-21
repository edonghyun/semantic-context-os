package io.scos.server;

import io.scos.mcp.McpStdioServer;
import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;

/**
 * 조립 루트. 모듈 경계는 PublicBoundaryRulesTest(ArchUnit)가 검사한다. 명령: {@code import --file=<units.json>
 * [--space=<id>]}, {@code source add …}, {@code collect --source=<id> [--mode=snapshot]}, {@code
 * project}, {@code cycle}, {@code mcp}(stdio). stdout은 MCP 전용이라 배너·로그는 꺼져 있거나
 * stderr다(logback-spring.xml).
 */
@SpringBootApplication(scanBasePackages = "io.scos")
public class ScosApplication {
  public static void main(String[] args) {
    boolean mcpMode = args.length > 0 && args[0].equals("mcp");
    boolean daemonMode = args.length > 0 && args[0].equals("daemon");
    SpringApplication app = new SpringApplication(ScosApplication.class);
    // mcp 모드는 프로필 mcp(읽기 전용 연결·Flyway validate만·stdio 수명주기, McpRuntimeConfiguration)로 뜬다. 서버 시작은
    // SmartLifecycle이 한다. daemon 모드는 프로필 daemon(감독 루프, DaemonConfiguration).
    if (mcpMode) app.setAdditionalProfiles("mcp");
    if (daemonMode) app.setAdditionalProfiles("daemon");
    // 웹(Actuator HTTP)은 daemon 모드만(ADR-0020 §3). yml의 spring.main.web-application-type은 환경 바인딩이
    // 이 값을 덮어쓰므로 쓰지 않는다. SpringApplicationBuilder로 띄우는 테스트는 .web(NONE)을 직접 준다.
    app.setWebApplicationType(daemonMode ? WebApplicationType.SERVLET : WebApplicationType.NONE);
    var context = app.run(args);
    // daemon 모드: 루프가 끝날 때까지(SIGTERM → 종료 훅 → SmartLifecycle.stop) 프로세스를 살려 둔다
    if (daemonMode) {
      try {
        context.getBean(CollectionDaemon.class).awaitTermination();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      // SIGTERM: 종료 훅이 컨텍스트를 닫는 중이다 — 종료 코드는 JVM이 정한다(143). 닫힌 컨텍스트에 exit를 묻지 않는다.
      if (!context.isActive()) return;
    }
    // mcp 모드: stdin EOF(클라이언트 종료)까지 기다렸다가 컨텍스트를 닫는다(lifecycle stop → 서버 close). 먼저 끝내면 initialize
    // 전에 종료되고(1차),
    // 기다리지 않으면 Spring 컨텍스트 때문에 EOF 뒤에도 JVM이 남는다(2차) — 둘 다 하네스 실행에서 확인.
    if (mcpMode) {
      try {
        context.getBean(McpStdioServer.class).awaitStdinEof();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    System.exit(SpringApplication.exit(context));
  }

  /** 시각은 항상 이 빈으로 읽는다(ADR-0002). `harness` 프로필은 하네스 어댑터 JAR의 고정 시각 빈이 대신한다(ADR-0017). */
  @Bean
  @Profile("!harness")
  Clock clock() {
    return Clock.systemUTC();
  }
}
