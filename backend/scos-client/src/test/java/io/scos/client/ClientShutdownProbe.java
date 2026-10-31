package io.scos.client;

import io.scos.client.collect.CollectLock;
import io.scos.client.collect.CollectionReport;
import io.scos.client.collect.CollectionTask;
import io.scos.support.HoldGate;
import io.scos.support.LogCapture;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/** 취소에 반응하지 않는 수집 작업의 종료 로그를 실제 stdio 수명으로 관찰한다. */
public final class ClientShutdownProbe {
  private ClientShutdownProbe() {}

  public static void main(String[] args) throws Exception {
    Path root = Path.of(args[0]);
    Path projects = Files.createDirectories(root.resolve("projects"));
    Map<String, String> env =
        Map.of(
            "SCOS_HOME",
            root.resolve("home").toString(),
            "SCOS_CLAUDE_PROJECTS",
            projects.toString(),
            "SCOS_QUERY_LOG",
            "");
    HoldGate entered = new HoldGate(Duration.ofMinutes(1));
    try (var log = LogCapture.of(CollectionCycles.class);
        var session = ScosStdioSession.observingShutdownTimeout(env, c -> stalled(c, entered))) {
      entered.awaitHeld("given 준비 실패: 수집 시작");
      int exitCode = session.finish();
      var contender = CollectLock.tryAcquire(root.resolve("home/collect.lock"));
      boolean lockHeld = contender.isEmpty();
      contender.ifPresent(CollectLock::close);
      Files.writeString(
          root.resolve("result.json"),
          ClientJson.MAPPER
              .createObjectNode()
              .put("exitCode", exitCode)
              .put("lockHeld", lockHeld)
              .set(
                  "events",
                  ClientJson.MAPPER.valueToTree(log.events("client.collection.shutdown_timed_out")))
              .toString());
    }
    // 종료 상한 뒤 남은 데몬 작업과 OS 잠금은 프로세스 종료가 거둔다.
    System.exit(0);
  }

  private static CollectionTask stalled(CollectionTask delegate, HoldGate entered) {
    return new CollectionTask() {
      @Override
      public CollectionReport runOnce() {
        entered.hold();
        return delegate.runOnce();
      }

      @Override
      public CollectionReport fullReproject() {
        return delegate.fullReproject();
      }

      @Override
      public void cancel() {
        delegate.cancel();
      }
    };
  }
}
