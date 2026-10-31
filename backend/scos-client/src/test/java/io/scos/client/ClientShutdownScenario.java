package io.scos.client;

import io.scos.support.ChildJvm;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

final class ClientShutdownScenario {
  record Result(int exitCode, JsonNode events, int clientExitCode, boolean lockHeld) {}

  private final Path root;

  ClientShutdownScenario(Path root) {
    this.root = root;
  }

  Result run() throws Exception {
    Process process =
        ChildJvm.start(ClientShutdownProbe.class, root.resolve("child.log"), root.toString());
    try {
      if (!process.waitFor(30, TimeUnit.SECONDS))
        throw new IllegalStateException(
            "자식 JVM 종료 상한 초과: " + ChildJvm.readLog(root.resolve("child.log")));
      JsonNode result =
          JsonMapper.builder().build().readTree(Files.readString(root.resolve("result.json")));
      return new Result(
          process.exitValue(),
          result.path("events"),
          result.path("exitCode").asInt(),
          result.path("lockHeld").asBoolean());
    } finally {
      if (process.isAlive()) process.destroyForcibly().waitFor();
    }
  }
}
