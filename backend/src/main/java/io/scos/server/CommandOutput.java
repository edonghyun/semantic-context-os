package io.scos.server;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * 조회 명령의 결과 출력(TS writeOutput = console.log: 한 번에 한 덩어리 + 줄바꿈). 제품은 stdout이다 — 로그는 모두
 * stderr라(ADR-0023) stdout에는 결과만 남아 파이프로 읽을 수 있다. mcp 밖에서 stdout을 쓰는 곳은 이 클래스 하나다(ADR-0024 §3).
 */
@FunctionalInterface
public interface CommandOutput {
  void write(String output);

  /** stdout에 UTF-8로 쓴다(플랫폼 기본 인코딩이 무엇이든 한글이 깨지지 않게). */
  static CommandOutput stdout() {
    PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
    return output -> {
      out.print(output);
      out.print('\n');
      out.flush();
    };
  }
}
