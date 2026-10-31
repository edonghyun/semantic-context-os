package io.scos.server;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * 명령이 사람에게 보여 주는 출력(한 번에 한 덩어리 + 줄바꿈). 조회 결과는 stdout이다 — 로그는 모두 stderr라(ADR-0023) stdout에는 결과만 남아
 * 파이프로 읽을 수 있다. 사용법 오류 문장은 stderr다 — 로그 줄 모양이 아니라 그 문장 그대로다(logging.md §6). mcp 밖에서 표준 스트림을 쓰는 곳은 이
 * 클래스 하나다(ADR-0024 §3).
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

  /** stderr에 UTF-8로 쓴다. 사용법 오류처럼 결과가 아닌 사람용 문장(로그 형식을 거치지 않는다). */
  static CommandOutput stderr() {
    PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
    return output -> {
      err.print(output);
      err.print('\n');
      err.flush();
    };
  }
}
