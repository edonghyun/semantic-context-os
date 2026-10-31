package io.scos.harness.evaluation.baseline;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/** 외부 명령의 stdout을 읽고 종료 코드를 검증한다. 매치 없음 코드는 빈 출력으로 정규화한다. */
final class ExternalCommand {
  private final String name;
  private final Set<Integer> emptyExitCodes;

  ExternalCommand(String name, Set<Integer> emptyExitCodes) {
    this.name = name;
    this.emptyExitCodes = Set.copyOf(emptyExitCodes);
  }

  String run(List<String> command) {
    try {
      Process process =
          new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
      process.getOutputStream().close();
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      int code = process.waitFor();
      if (code == 0) return output;
      if (emptyExitCodes.contains(code)) return "";
      throw new IllegalStateException(name + " exited " + code);
    } catch (IOException e) {
      throw new IllegalStateException(name + " could not run", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(name + " interrupted", e);
    }
  }
}
