package io.scos.harness.testing;

import io.scos.harness.fixture.FixtureSpec;
import io.scos.harness.mcp.JsonRpcStdioClient;
import io.scos.harness.server.ServerLauncher;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** FakeMcpServer를 같은 JVM 클래스패스로 띄우는 런처 — 하네스 자체 테스트 전용. */
public final class FakeServerLauncher implements ServerLauncher {
  private final Map<String, String> extraEnv;

  public FakeServerLauncher(Map<String, String> extraEnv) {
    this.extraEnv = extraEnv;
  }

  public static JsonRpcStdioClient.Launch launch(Path cwd, Map<String, String> extraEnv) {
    String java =
        Path.of(
                System.getProperty("harness.java.home", System.getProperty("java.home")),
                "bin",
                "java")
            .toString();
    Map<String, String> env = new HashMap<>(extraEnv);
    env.put("PATH", System.getenv().getOrDefault("PATH", "/usr/bin:/bin"));
    return new JsonRpcStdioClient.Launch(
        List.of(java, "-cp", System.getProperty("java.class.path"), FakeMcpServer.class.getName()),
        env,
        cwd,
        cwd.resolve("fake-stderr.log"));
  }

  @Override
  public String implementationId() {
    return "fake";
  }

  @Override
  public Path prepare(FixtureSpec fixture, Path workDir) {
    return workDir.resolve("no-store");
  }

  @Override
  public JsonRpcStdioClient.Launch launch(
      FixtureSpec fixture, Path workDir, Path store, HarnessEnv env) {
    Map<String, String> e = new HashMap<>(extraEnv);
    if (e.containsKey("FAKE_MISS_LOG")) e.put("FAKE_MISS_LOG", env.missLog().toString());
    return launch(workDir, e);
  }
}
