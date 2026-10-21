package io.scos.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;
import org.awaitility.core.TerminalFailureException;

/** 장애 테스트용 자식 JVM(같은 클래스패스·toolchain JDK). 순서는 마커 파일로 맞춘다 — sleep 동기화가 아니다. */
public final class ChildJvm {
  private static final Duration POLL_INTERVAL = Duration.ofMillis(10);

  private ChildJvm() {}

  public static Process start(Class<?> main, Path log, String... args) throws IOException {
    return start(main, List.of(), log, args);
  }

  /** {@link #start}와 같되 JVM 옵션(예: 힙 상한 `-Xmx512m`)을 붙인다 — 자원 한계에서의 동작을 재는 프로브. */
  public static Process start(Class<?> main, List<String> jvmOptions, Path log, String... args)
      throws IOException {
    String javaBin =
        System.getProperty("scos.java.home", System.getProperty("java.home")) + "/bin/java";
    List<String> cmd = new ArrayList<>(List.of(javaBin));
    cmd.addAll(jvmOptions);
    cmd.addAll(List.of("-cp", System.getProperty("java.class.path"), main.getName()));
    cmd.addAll(List.of(args));
    return new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log.toFile()).start();
  }

  /** 실제 실행 JAR(`java -jar`)을 환경변수로 설정해 띄운다 — 운영 기동 경로 그대로(ADR-0020 §7). */
  public static Process startJar(Path jar, Path log, Map<String, String> env, String... args)
      throws IOException {
    String javaBin =
        System.getProperty("scos.java.home", System.getProperty("java.home")) + "/bin/java";
    List<String> cmd = new ArrayList<>(List.of(javaBin, "-jar", jar.toString()));
    cmd.addAll(List.of(args));
    ProcessBuilder pb =
        new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log.toFile());
    pb.environment().putAll(env);
    return pb.start();
  }

  /** {@link #startJar}와 같되 stdout과 stderr를 따로 받는다 — 조회 명령의 결과(stdout)에 로그가 섞이지 않는지 보는 곳. */
  public static Process startJarSeparated(
      Path jar, Path stdout, Path stderr, Map<String, String> env, String... args)
      throws IOException {
    String javaBin =
        System.getProperty("scos.java.home", System.getProperty("java.home")) + "/bin/java";
    List<String> cmd = new ArrayList<>(List.of(javaBin, "-jar", jar.toString()));
    cmd.addAll(List.of(args));
    ProcessBuilder pb =
        new ProcessBuilder(cmd).redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
    pb.environment().putAll(env);
    return pb.start();
  }

  /**
   * bootJar를 PropertiesLauncher로 띄워 loaderPath(디렉터리·JAR)를 클래스패스에 얹는다 — 하네스 어댑터와 같은 방식(테스트 전용 조립을 제품
   * JAR 밖에서 주입).
   */
  public static Process startJarWithLoaderPath(
      Path jar, Path loaderPath, Path log, Map<String, String> env, String... args)
      throws IOException {
    String javaBin =
        System.getProperty("scos.java.home", System.getProperty("java.home")) + "/bin/java";
    List<String> cmd =
        new ArrayList<>(
            List.of(
                javaBin,
                "-Dloader.path=" + loaderPath,
                "-cp",
                jar.toString(),
                "org.springframework.boot.loader.launch.PropertiesLauncher"));
    cmd.addAll(List.of(args));
    ProcessBuilder pb =
        new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log.toFile());
    pb.environment().putAll(env);
    return pb.start();
  }

  /** 마커 파일이 생길 때까지. 상한은 hang 방지용이다. */
  public static void awaitMarker(Path marker, long timeoutMs) throws InterruptedException {
    awaitUntil("marker not created: " + marker, () -> Files.exists(marker), timeoutMs);
  }

  public static void touch(Path marker) throws IOException {
    Files.createDirectories(marker.getParent());
    Files.writeString(marker, "1");
  }

  public static String readLog(Path log) throws IOException {
    return Files.exists(log) ? Files.readString(log) : "";
  }

  /** 실제 조건(마커·DB 상태)만 기다린다. 상한은 hang 방지용. */
  public static void awaitCondition(String what, BooleanSupplier condition, long timeoutMs)
      throws InterruptedException {
    awaitUntil("condition not met: " + what, condition, timeoutMs);
  }

  /**
   * 조건 폴링의 유일한 구현(Awaitility). 조건은 부른 스레드에서 확인한다(스레드 안전하지 않은 관찰도 그대로 쓰게). 지연 없이 바로 확인하고 짧게 다시 본다 —
   * 시간을 때우는 pollDelay는 쓰지 않는다(TESTING §7). Awaitility는 인터럽트를 보지 않으므로 확인마다 인터럽트 표시를 보고, 서 있으면 표시를
   * 지우고 InterruptedException으로 끝낸다 — JUnit {@code @Timeout}이 상한을 기다리지 않고 대기를 끊는다.
   */
  private static void awaitUntil(String failure, BooleanSupplier condition, long timeoutMs)
      throws InterruptedException {
    try {
      Awaitility.await(failure)
          .pollInSameThread()
          .pollDelay(Duration.ZERO)
          .pollInterval(POLL_INTERVAL)
          .atMost(Duration.ofMillis(timeoutMs))
          .failFast("interrupted", () -> Thread.currentThread().isInterrupted())
          .until(condition::getAsBoolean);
    } catch (TerminalFailureException e) {
      Thread.interrupted();
      InterruptedException interrupted = new InterruptedException(failure);
      interrupted.initCause(e);
      throw interrupted;
    } catch (ConditionTimeoutException e) {
      throw new IllegalStateException(failure, e);
    }
  }

  /**
   * 자식의 `RESULT <phase> k=v k=v …` 줄(결과 프로토콜). 없으면 로그 전체를 담아, 단계가 없거나 k=v가 아닌 토큰이 있으면 그 줄을 담아
   * 실패시킨다.
   */
  public record ProbeResult(String phase, Map<String, String> values) {
    public String get(String key) {
      String v = values.get(key);
      if (v == null) throw new IllegalStateException("RESULT has no " + key + ": " + values);
      return v;
    }

    public int getInt(String key) {
      return Integer.parseInt(get(key));
    }
  }

  public static ProbeResult readResult(Path log) throws IOException {
    String text = readLog(log);
    String line =
        text.lines()
            .filter(l -> l.startsWith("RESULT "))
            .findFirst()
            .orElseThrow(
                () -> new IllegalStateException("no RESULT line in " + log + ":\n" + text));
    String[] parts = Pattern.compile(" ").split(line, -1);
    if (parts.length < 2 || parts[1].isEmpty())
      throw new IllegalStateException("RESULT line has no phase: " + line);
    Map<String, String> values = new LinkedHashMap<>();
    for (int i = 2; i < parts.length; i++) {
      int eq = parts[i].indexOf('=');
      // 값에 공백이 있으면 토큰이 갈라진다 — 조용히 버리지 않는다(F15 T15.2)
      if (eq <= 0)
        throw new IllegalStateException(
            "RESULT token '" + parts[i] + "' is not k=v (a value with spaces?): " + line);
      values.put(parts[i].substring(0, eq), parts[i].substring(eq + 1));
    }
    return new ProbeResult(parts[1], values);
  }
}
