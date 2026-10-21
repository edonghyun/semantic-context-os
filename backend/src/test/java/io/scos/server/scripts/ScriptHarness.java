package io.scos.server.scripts;

import io.scos.support.Eventually;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.springframework.util.FileSystemUtils;

/**
 * 운영 래퍼 스크립트(deploy/java/bin)를 실제 /bin/bash로 실행하는 연결. 임시 운영 디렉터리(SCOS_HOME)와 가짜 명령 디렉터리(PATH 맨 앞)를
 * 만든다 — java·docker·df는 받은 인자와 환경을 파일로 남기는 가짜 스크립트로 바꾼다(실제 JVM·DB·디스크를 건드리지 않는다). 본문은 "어떤 환경에서 실행하면
 * 종료 코드·파일·로그가 어떻게 되는가"만 쓴다.
 */
final class ScriptHarness implements AutoCloseable {
  static final Path BIN =
      Path.of(System.getProperty("scos.deploy.dir", "../deploy/java"))
          .resolve("bin")
          .toAbsolutePath();

  /** 스크립트·가짜 명령(자식 프로세스) 대기 상한 — hang 방지용이며 성공 근거가 아니다. */
  static final Duration PROCESS_LIMIT = Duration.ofSeconds(30);

  /** 끝난 스크립트의 종료 코드와 두 출력. */
  record Result(int exitCode, String stdout, String stderr) {}

  final Path home;
  final Path fakes;
  final Path records;
  final Map<String, String> env = new HashMap<>();
  private final List<Process> started = new ArrayList<>();

  ScriptHarness() {
    try {
      home = Files.createTempDirectory("scos-home-");
      fakes = Files.createDirectory(home.resolve("fake-bin"));
      records = Files.createDirectory(home.resolve("records"));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    env.put("SCOS_HOME", home.toString());
    env.put("SCOS_JAR", home.resolve("scos-backend.jar").toString());
    env.put("SCOS_JAVA", fakes.resolve("java").toString());
    env.put("SCOS_RECORDS", records.toString());
    env.put("PATH", fakes + ":/usr/bin:/bin:/usr/sbin:/sbin");
  }

  /** 권한 600의 .env(키 하나). */
  ScriptHarness withEnvFile() {
    return withEnvFile("rw-------");
  }

  ScriptHarness withEnvFile(String permissions) {
    Path file = home.resolve(".env");
    write(file, "SCOS_TEST_FROM_ENV_FILE=loaded\n");
    setPermissions(file, permissions);
    return this;
  }

  /** PATH 맨 앞에 가짜 명령(bash 본문)을 둔다. */
  ScriptHarness fake(String name, String body) {
    Path file = fakes.resolve(name);
    write(file, "#!/bin/bash\n" + body + "\n");
    setPermissions(file, "rwx------");
    return this;
  }

  /**
   * 가짜 java: 인자를 records/java.args에, 고른 환경변수를 records/java.env에 남기고 FAKE_JAVA_EXIT로 끝난다.
   * FAKE_JAVA_WAIT가 있으면 TERM을 받을 때까지 기다렸다가 records/java.term을 남기고 143으로 끝난다(JVM 종료 훅과 같은 코드).
   */
  ScriptHarness fakeJava() {
    return fake(
        "java",
        """
        printf '%s\\n' "$@" > "$SCOS_RECORDS/java.args"
        {
          echo "SCOS_DAEMON_MAINTENANCE_GRACE_DAYS=${SCOS_DAEMON_MAINTENANCE_GRACE_DAYS-}"
          echo "SCOS_QUERY_LOG=${SCOS_QUERY_LOG-}"
          echo "SCOS_TEST_FROM_ENV_FILE=${SCOS_TEST_FROM_ENV_FILE-}"
        } > "$SCOS_RECORDS/java.env"
        if [ -n "${FAKE_JAVA_WAIT-}" ]; then
          trap 'touch "$SCOS_RECORDS/java.term"; kill "$sleeper" 2>/dev/null; exit 143' TERM
          touch "$SCOS_RECORDS/java.started"
          sleep 30 & sleeper=$!
          wait "$sleeper"
          exit 0
        fi
        exit "${FAKE_JAVA_EXIT:-0}"
        """);
  }

  /** 가짜 명령 하나를 끝을 기다리지 않고 직접 띄운다 — 가짜 자체의 계약을 보는 ScriptHarnessTest용(F15 T15.2). */
  Process startFake(String name, String... args) {
    List<String> command = new ArrayList<>(List.of(fakes.resolve(name).toString()));
    command.addAll(List.of(args));
    ProcessBuilder pb =
        new ProcessBuilder(command)
            .directory(home.toFile())
            .redirectOutput(output("fake-" + name, "out").toFile())
            .redirectError(output("fake-" + name, "err").toFile());
    pb.environment().clear();
    pb.environment().putAll(env);
    try {
      Process p = pb.start();
      started.add(p);
      return p;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** 가짜 명령 하나를 직접 실행하고 끝을 기다린다. 출력은 records/fake-<이름>.out·err. */
  Result runFake(String name, String... args) {
    Process p = startFake(name, args);
    try {
      if (!p.waitFor(PROCESS_LIMIT.toSeconds(), TimeUnit.SECONDS))
        throw new AssertionError("가짜 " + name + "가 시간 안에 끝나지 않았다");
      return new Result(
          p.exitValue(), read(output("fake-" + name, "out")), read(output("fake-" + name, "err")));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }

  Result run(String script, String... args) {
    Process p = start(script, args);
    try {
      if (!p.waitFor(PROCESS_LIMIT.toSeconds(), TimeUnit.SECONDS)) {
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
        throw new AssertionError("스크립트가 시간 안에 끝나지 않았다: " + script);
      }
      return new Result(p.exitValue(), read(output(script, "out")), read(output(script, "err")));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }

  /**
   * 끝을 기다리지 않고 띄운다(신호 시험용). 출력은 파이프가 아니라 records/<script>.out·err 파일로 간다 — launchd처럼.
   * Process.destroy()는 SIGTERM을 보낸 뒤 파이프를 닫으므로, 파이프였다면 래퍼의 마지막 로그 쓰기가 SIGPIPE(141)로 끝난다.
   */
  Process start(String script, String... args) {
    List<String> command = new ArrayList<>(List.of("/bin/bash", BIN.resolve(script).toString()));
    command.addAll(List.of(args));
    ProcessBuilder pb =
        new ProcessBuilder(command)
            .directory(home.toFile())
            .redirectOutput(output(script, "out").toFile())
            .redirectError(output(script, "err").toFile());
    pb.environment().clear();
    pb.environment().putAll(env);
    try {
      Process p = pb.start();
      started.add(p);
      return p;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private Path output(String script, String stream) {
    return records.resolve(script + "." + stream);
  }

  /**
   * GNU coreutils처럼 도는 가짜 stat의 본문: {@code -f}는 파일 시스템 정보 옵션이라 정보를 stdout에 쓰고 실패하고, {@code -c '%a'
   * 파일}이 권한을 낸다(진짜 stat 없이 ls -ln으로 계산). macOS에서 GNU 갈래를 지나게 한다(리뷰 B I1). 같은 본문이 GNU(Debian)에서도 같게
   * 도는지는 {@code GnuWrapperTest}가 본다.
   */
  static final String GNU_STAT_FAKE =
      """
      if [ "$1" = "-f" ]; then
        echo "  File: \\"$3\\""
        echo "    ID: 0 Namelen: 255 Type: overlayfs"
        exit 1
      fi
      if [ "$1" = "-c" ] && [ "$2" = "%a" ]; then
        # 권한 문자열(ls -ln 첫 열 rwx 9자)을 8진수로 — 진짜 stat을 부르지 않아 BSD·GNU 어디서나 같다(F15 T15.0)
        # s·t(소문자)는 실행 비트가 있는 특수 비트, S·T는 실행 비트 없는 특수 비트 — GNU stat %a처럼 특수 비트를 앞에 붙인다
        /bin/ls -ln "$3" | awk '{
          p = $1; m = 0
          for (i = 2; i <= 10; i++) { c = substr(p, i, 1); m = m * 2 + (c != "-" && c != "S" && c != "T") }
          sp = (substr(p, 4, 1) ~ /[sS]/) * 4 + (substr(p, 7, 1) ~ /[sS]/) * 2 + (substr(p, 10, 1) ~ /[tT]/)
          if (sp > 0) printf "%o%03o\\n", sp, m; else printf "%o\\n", m
        }'
        exit
      fi
      echo "stat: unsupported $*" >&2
      exit 1
      """;

  ScriptHarness gnuStat() {
    return fake("stat", GNU_STAT_FAKE);
  }

  /** 프로세스에 신호를 보낸다(kill -<신호>). */
  static void signal(Process p, String name) {
    try {
      Process kill = new ProcessBuilder("kill", "-" + name, String.valueOf(p.pid())).start();
      if (kill.waitFor() != 0) throw new AssertionError("kill -" + name + " 실패");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }

  /** 파일이 생길 때까지 기다린다(상한 PROCESS_LIMIT — 스크립트가 남기는 파일). */
  void awaitFile(Path file) {
    Eventually.await("파일 " + file, () -> Files.exists(file), PROCESS_LIMIT);
  }

  String read(Path file) {
    try {
      return Files.readString(file);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static String permissions(Path file) {
    try {
      return PosixFilePermissions.toString(Files.getPosixFilePermissions(file));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static void write(Path file, String content) {
    try {
      Files.writeString(file, content);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static void setPermissions(Path file, String permissions) {
    try {
      Set<PosixFilePermission> perms = PosixFilePermissions.fromString(permissions);
      Files.setPosixFilePermissions(file, perms);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public void close() {
    for (Process p : started) {
      p.descendants().forEach(ProcessHandle::destroyForcibly);
      p.destroyForcibly();
    }
    FileSystemUtils.deleteRecursively(home.toFile());
  }
}
