package io.scos.server;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;

/**
 * 조립 루트. 모듈 경계는 PublicBoundaryRulesTest(ArchUnit)가 검사한다. 명령: {@code import --file=<units.json>
 * [--space=<id>] [--embedding-generation=<세대>]}, {@code source add …}, {@code collect --source=<id>
 * [--mode=snapshot]}, {@code project}, {@code cycle}, {@code reembed --space=<id>}, {@code
 * mcp}(stdio). 전체 목록은 Commands.USAGE. stdout은 MCP 전용이라 배너·로그는 꺼져 있거나 stderr다(logback-spring.xml).
 */
@SpringBootApplication(scanBasePackages = "io.scos")
public class ScosApplication {
  public static void main(String[] args) {
    LaunchMode mode = LaunchMode.of(args);
    SpringApplication app = new SpringApplication(ScosApplication.class);
    mode.configure(app);
    var context = app.run(args);
    if (mode.awaitEnd(context)) System.exit(SpringApplication.exit(context));
  }

  /** 시각은 항상 이 빈으로 읽는다(ADR-0002). `harness` 프로필은 하네스 어댑터 JAR의 고정 시각 빈이 대신한다(ADR-0017). */
  @Bean
  @Profile("!harness")
  Clock clock() {
    return Clock.systemUTC();
  }
}
