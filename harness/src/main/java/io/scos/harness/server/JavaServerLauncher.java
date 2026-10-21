package io.scos.harness.server;

import io.scos.harness.fixture.FixtureSpec;
import io.scos.harness.mcp.JsonRpcStdioClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Java 구현(backend 실행 JAR)을 띄운다. prepare = 제품 적재 명령(`import --file documents.json`, 제품 유스케이스 경유 —
 * 하네스 전용 SQL 없음), launch = `mcp`(stdio). 고정 시각·카세트·미스 로그는 TS와 같은 환경변수 이름으로 넘기고, 하네스 어댑터 JAR(제품 밖)이
 * `harness` 프로필에서 Clock·Embedder 구현을 바꾼다(ADR-0017). PostgreSQL 접속은 호출자(테스트)가 준다.
 */
public final class JavaServerLauncher implements ServerLauncher {
  public record Database(String jdbcUrl, String user, String password) {}

  /**
   * 제품 bootJar의 Main-Class는 JarLauncher다(재검토 N-3). 하네스만 JAR 안의 PropertiesLauncher를 명시 호출해 어댑터 JAR을
   * 얹는다.
   */
  static final String PROPERTIES_LAUNCHER =
      "org.springframework.boot.loader.launch.PropertiesLauncher";

  private final Path jar;
  private final Path adaptersJar;
  private final Path javaHome;
  private final Database db;

  /**
   * @param adaptersJar 하네스 어댑터 JAR(scos-harness-adapters.jar, 제품 bootJar 밖) — `-Dloader.path`로
   *     얹는다(ADR-0017, 리뷰 I-6)
   */
  public JavaServerLauncher(Path jar, Path adaptersJar, Path javaHome, Database db) {
    this.jar = jar;
    this.adaptersJar = adaptersJar;
    this.javaHome = javaHome;
    this.db = db;
  }

  @Override
  public String implementationId() {
    return "java";
  }

  private Map<String, String> env(FixtureSpec fixture, Path workDir, Path missLog) {
    Map<String, String> e = new HashMap<>();
    e.put("PATH", System.getenv().getOrDefault("PATH", "/usr/bin:/bin"));
    e.put("HOME", System.getenv().getOrDefault("HOME", workDir.toString()));
    e.put("SCOS_DB_URL", db.jdbcUrl());
    e.put("SCOS_DB_USER", db.user());
    e.put("SCOS_DB_PASSWORD", db.password());
    e.put("SCOS_SPACE_ID", fixture.spaceId());
    e.put("SPRING_PROFILES_ACTIVE", "harness");
    e.put("SCOS_RERANK_PROVIDER", "gemini"); // 하네스 프로필의 카세트 생성기가 재정렬 응답을 재생한다(P3)
    e.put("SCOS_HARNESS_NOW", fixture.nowIso());
    e.put("SCOS_HARNESS_CASSETTE", fixture.cassettePath().toString());
    e.put("SCOS_HARNESS_MISS_LOG", missLog.toString());
    return e;
  }

  private String java() {
    return javaHome.resolve("bin").resolve("java").toString();
  }

  @Override
  public Path prepare(FixtureSpec fixture, Path workDir) throws IOException {
    Path log = workDir.resolve("import.log");
    List<String> cmd =
        List.of(
            java(),
            "-Duser.language=en",
            "-Dloader.path=" + adaptersJar,
            "-cp",
            jar.toString(),
            PROPERTIES_LAUNCHER,
            "import",
            "--file=" + fixture.documentsPath(),
            "--space=" + fixture.spaceId());
    ProcessBuilder pb =
        new ProcessBuilder(cmd)
            .directory(workDir.toFile())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile());
    pb.environment().clear();
    pb.environment().putAll(env(fixture, workDir, workDir.resolve("import-misses.jsonl")));
    Process p = pb.start();
    try {
      if (!p.waitFor(180, TimeUnit.SECONDS)) {
        p.destroyForcibly();
        throw new IOException("import timed out: " + Files.readString(log));
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException(e);
    }
    if (p.exitValue() != 0)
      throw new IOException(
          "import failed (exit " + p.exitValue() + "):\n" + Files.readString(log));
    return workDir.resolve("postgres"); // 저장소는 DB — 경로는 표식일 뿐
  }

  @Override
  public JsonRpcStdioClient.Launch launch(
      FixtureSpec fixture, Path workDir, Path store, HarnessEnv env) {
    Map<String, String> e = env(fixture, workDir, env.missLog());
    e.put("SCOS_HARNESS_NOW", env.nowIso());
    e.put("SCOS_HARNESS_CASSETTE", env.cassette().toString());
    e.put("SCOS_SPACE_ID", env.spaceId());
    List<String> cmd =
        List.of(
            java(),
            "-Duser.language=en",
            "-Dloader.path=" + adaptersJar,
            "-cp",
            jar.toString(),
            PROPERTIES_LAUNCHER,
            "mcp");
    return new JsonRpcStdioClient.Launch(cmd, e, workDir, workDir.resolve("server-stderr.log"));
  }
}
