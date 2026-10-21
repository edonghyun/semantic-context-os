package io.scos.collection.internal.credentials;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.CallFailureException;
import io.scos.support.BackgroundThread;
import io.scos.support.Eventually;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * `command:<셸 명령>`(TS CommandCredentialResolver): stdout의 앞뒤 공백을 뗀 값. 실패 오류에는 종료 코드만 싣고
 * stdout·stderr는 싣지 않는다.
 */
@Tag("process")
@DisplayName("command: 자격 증명은 명령 stdout이고, 실패에 출력을 싣지 않는다(TS)")
class CommandCredentialsTest {
  static final String SECRET = "xoxb-command-secret";

  /** 시간 상한 사례의 상한: 상한을 넘기게 할 명령(sleep 6)보다 충분히 짧고, 부하 속에서도 셸이 자식을 띄울 만큼 길다. */
  static final Duration SHORT_TIMEOUT = Duration.ofSeconds(2);

  /** 시간 상한이 계약이 아닌 사례의 명령 상한(제품 설정값) — 결과보다 먼저 오지 않는다. */
  static final Duration PATIENT_TIMEOUT = Duration.ofSeconds(10);

  /** 이 프로세스 안의 대기(pid 기록·부른 스레드 합류) 상한. */
  static final Duration WAIT_LIMIT = Duration.ofSeconds(10);

  /**
   * 상한(SHORT_TIMEOUT)을 넘겨 도는 명령. PIT 감시(15s)보다 일찍 끝나야 상한을 무시하는 변이가 감시 시간 초과가 아니라 단언으로 죽는다
   * (MUTATION.md "F2 리뷰 반영").
   */
  static final String SLEEP_PAST_TIMEOUT = "sleep 6";

  /** 끝내지 않으면 테스트보다 오래 사는 자식 — 스스로 끝나 "끝냈다"로 보이지 않게. stdout을 쥐지 않는다. */
  static final String LONG_LIVED_CHILD = "sleep 30 > /dev/null";

  /**
   * 출력 상한(1 MiB)을 넘는 명령. 크기 사례는 시간 상한이 먼저 오지 않게 patient(10s)로 푼다 — 부하가 큰 실행에서 500ms 안에 1 MiB를 못 내
   * 시간 초과로 실패한 적이 있다.
   */
  static final String OVERSIZED_OUTPUT = "head -c 2000000 /dev/zero | tr '\\0' a";

  /** 출력 상한 1 MiB를 손으로 쓴 값(제품 상수를 기대값으로 쓰지 않는다). */
  static final int ONE_MIB = 1_048_576;

  /** 정확히 n바이트를 내는 명령. */
  static String outputOf(int bytes) {
    return "head -c " + bytes + " /dev/zero | tr '\\0' a";
  }

  /**
   * SIGPIPE를 무시하고 끝없이 쓰는 명령(fork 없이 셸 내장 echo만) — 읽기를 멈춰도 스스로 끝나지 않는다. 자식을 띄우지 않아 강제 종료 뒤 고아가 남지
   * 않는다.
   */
  static final String OVERSIZED_OUTPUT_IGNORING_SIGPIPE =
      "trap '' PIPE; while :; do echo " + "a".repeat(1000) + "; done 2>/dev/null";

  /** 강제 종료된 프로세스가 사라지기를 기다리는 상한. 끝내지 않은 자식을 찾는 실패도 이만큼 걸린다(PIT 감시보다 짧게). */
  static final Duration PROCESS_LIMIT = Duration.ofSeconds(3);

  /** 테스트 끝 정리에서 막 끝낸 프로세스가 사라지기를 기다리는 상한(모두 합쳐서). PIT 감시보다 충분히 짧다. */
  static final Duration PROCESS_SETTLE_LIMIT = Duration.ofSeconds(5);

  /** 정적 초기화는 PIT가 어느 테스트의 커버리지로도 보지 않는다 — 대상은 인스턴스 필드로 만든다. */
  final CommandCredentials commands = new CommandCredentials(SHORT_TIMEOUT);

  /**
   * 시간 상한이 계약이 아닌 사례용(값·종료 코드·출력 상한·중단): 상한이 결과보다 먼저 오지 않는다. 짧은 상한으로 풀면 부하가 큰 실행에서 `exit 3`조차 상한에
   * 걸려 엉뚱한 실패가 났다.
   */
  final CommandCredentials patient = new CommandCredentials(PATIENT_TIMEOUT);

  @TempDir Path dir;

  /** 이 테스트가 시작된 시각(초 단위로 내림) — 프로세스 시작 시각과 비교해 pid 재사용을 가린다. */
  Instant testStarted;

  @BeforeEach
  void recordTestStart() {
    testStarted = Instant.now().truncatedTo(ChronoUnit.SECONDS);
  }

  /**
   * 테스트가 띄운 명령 프로세스는 테스트보다 오래 살지 않는다: 명령이 남긴 pid(이 테스트의 임시 디렉터리)와 이 JVM의 자손 가운데 **우리 프로세스로 확인된 것**만
   * 끝내고, 있었다면 실패한다. 우리 것 = 이 테스트가 시작한 뒤에 시작했고 명령줄이 이 테스트의 명령(임시 디렉터리 경로, sleep, 바쁜 루프)이다. 확인할 수 없는
   * pid(이미 끝나 재사용됐거나, 정보를 읽을 수 없는 남의 프로세스·회수 전 좀비)는 건드리지도 실패시키지도 않는다 — 이 기기는 30분 안에 pid가 한 바퀴 돈다.
   * 실패 메시지는 끝내기 전에 만든다(명령줄을 잃지 않게).
   */
  @AfterEach
  void noCommandProcessOutlivesTheTest() throws IOException {
    List<ProcessHandle> candidates = new ArrayList<>();
    try (Stream<Path> files = Files.list(dir)) {
      for (Path pidFile : files.filter(f -> f.toString().endsWith(".pid")).toList()) {
        String text = Files.readString(pidFile).strip();
        if (!text.isEmpty()) ProcessHandle.of(Long.parseLong(text)).ifPresent(candidates::add);
      }
    }
    ProcessHandle.current().descendants().forEach(candidates::add);
    List<ProcessHandle> ours = candidates.stream().distinct().filter(this::isOurs).toList();
    awaitExit(ours);
    List<ProcessHandle> leftovers = ours.stream().filter(this::isOurs).toList();
    List<String> described =
        leftovers.stream().map(p -> p.pid() + " " + p.info().commandLine().orElse("?")).toList();
    leftovers.forEach(
        p -> p.descendants().filter(this::isOurs).forEach(ProcessHandle::destroyForcibly));
    leftovers.forEach(ProcessHandle::destroyForcibly);

    assertThat(described).as("테스트가 끝난 뒤 남은 명령 프로세스").isEmpty();
  }

  /** 살아 있고, 이 테스트가 시작한 뒤에 시작했고, 명령줄이 이 테스트의 명령인 프로세스. 정보를 읽을 수 없으면 우리 것이 아니다. */
  boolean isOurs(ProcessHandle process) {
    if (!process.isAlive()) return false;
    ProcessHandle.Info info = process.info();
    Optional<Instant> started = info.startInstant();
    Optional<String> commandLine = info.commandLine();
    if (started.isEmpty() || commandLine.isEmpty() || started.get().isBefore(testStarted))
      return false;
    return commandLine.get().contains(dir.toString()) || runsOneOfOurCommands(commandLine.get());
  }

  /** 이 테스트가 띄우는 오래 도는 명령(sleep·바쁜 루프) — 같은 JVM의 다른 테스트가 띄운 프로세스는 보지 않는다. */
  static boolean runsOneOfOurCommands(String commandLine) {
    return commandLine.contains("sleep") || commandLine.contains("while :");
  }

  /** 막 끝낸 프로세스는 잠깐 살아 보일 수 있다 — 모두 합쳐 PROCESS_SETTLE_LIMIT까지 기다린다(남은 것이 없으면 바로 끝난다). */
  static void awaitExit(List<ProcessHandle> processes) {
    try {
      CompletableFuture.allOf(
              processes.stream().map(ProcessHandle::onExit).toArray(CompletableFuture[]::new))
          .get(PROCESS_SETTLE_LIMIT.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException | ExecutionException e) {
      // 남은 것은 호출자가 isAlive로 가려 끝낸다
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** 명령이 남긴 pid 파일의 프로세스가 끝날 때까지 기다리고, 아직 살아 있는 프로세스를 돌려준다. */
  Optional<ProcessHandle> survivorOf(Path pidFile) throws IOException {
    long pid = Long.parseLong(Files.readString(pidFile).strip());
    Eventually.await(
        "pid " + pid + " 종료",
        () -> ProcessHandle.of(pid).filter(ProcessHandle::isAlive).isEmpty(),
        PROCESS_LIMIT);
    return ProcessHandle.of(pid).filter(ProcessHandle::isAlive);
  }

  /** 다른 스레드에서 명령을 풀다가 중단한 결과: 부른 스레드, 명령이 남긴 pid 파일, 던져진 실패. */
  record Interrupted(BackgroundThread caller, Path pidFile, AtomicReference<Throwable> thrown) {
    CallFailureException failure() {
      return (CallFailureException) thrown.get();
    }
  }

  /**
   * 명령이 자식을 띄운 뒤 자기 pid를 남기면(실행 중) 부른 스레드를 인터럽트하고 끝나기를 기다린다. pid를 자식을 띄운 뒤에 써야 "실행 중"이 성립한다 — 먼저 쓰면
   * 인터럽트가 fork보다 앞서 자식이 고아가 될 수 있었다(부하 실행에서 남은 sleep 30).
   */
  Interrupted interruptWhileRunning() throws InterruptedException {
    Path pidFile = dir.resolve("command.pid");
    AtomicReference<Throwable> thrown = new AtomicReference<>();
    BackgroundThread caller =
        BackgroundThread.start(
            "credential-caller",
            () ->
                thrown.set(
                    catchThrowable(
                        () ->
                            patient.resolve(
                                LONG_LIVED_CHILD + " & echo $$ > " + pidFile + "; wait"))));
    Eventually.await("명령 pid 기록", () -> pidFile.toFile().length() > 0, WAIT_LIMIT);
    caller.interruptAndJoin(WAIT_LIMIT);
    return new Interrupted(caller, pidFile, thrown);
  }

  /** 명령을 시간 상한까지 돌린다. 실패 내용은 다른 테스트의 몫이다. */
  void runPastTimeout(String command) {
    var unused = catchThrowable(() -> commands.resolve(command));
  }

  @Nested
  @DisplayName("명령이 0으로 끝나면")
  class ExitsZero {
    @Test
    @DisplayName("stdout의 앞뒤 공백을 떼어 돌려준다")
    void returnsTrimmedStdout() {
      // when
      String resolved = patient.resolve("printf '" + SECRET + "\\n'");

      // then
      assertThat(resolved).isEqualTo(SECRET);
    }

    @Test
    @DisplayName("출력이 비면 거부한다")
    void rejectsEmptyOutput() {
      // when·then
      assertThatThrownBy(() -> patient.resolve("printf '  '"))
          .isInstanceOf(CallFailureException.class)
          .hasMessageContaining("empty output");
    }
  }

  @Nested
  @DisplayName("명령이 0이 아닌 코드로 끝나면")
  class ExitsNonZero {
    @Test
    @DisplayName("종료 코드를 담아 거부한다")
    void rejectsNonZeroExitWithCode() {
      // when·then
      assertThatThrownBy(() -> patient.resolve("exit 3"))
          .isInstanceOf(CallFailureException.class)
          .hasMessageContaining("exit code 3");
    }

    @Test
    @DisplayName("명령의 stdout·stderr는 오류에도 원인 예외에도 남지 않는다")
    void failureCarriesNoOutput() {
      // given
      String leaking = "echo " + SECRET + "; echo " + SECRET + " >&2; exit 1";

      // when·then
      assertThatThrownBy(() -> patient.resolve(leaking))
          .isInstanceOf(CallFailureException.class)
          .hasMessageNotContaining(SECRET)
          .hasNoCause();
    }
  }

  @Nested
  @DisplayName("명령이 시간 상한을 넘기면")
  class TimesOut {
    @Test
    @DisplayName("시간 초과로 거부한다")
    void rejectsWhenTimedOut() {
      // when·then
      assertThatThrownBy(() -> commands.resolve(SLEEP_PAST_TIMEOUT))
          .isInstanceOf(CallFailureException.class)
          .hasMessageContaining("timed out");
    }

    @Test
    @DisplayName("명령이 띄운 자식 프로세스까지 끝낸다")
    void timeoutKillsDescendants() throws IOException {
      // given
      Path pidFile = dir.resolve("child.pid");

      // when
      runPastTimeout(LONG_LIVED_CHILD + " & echo $! > " + pidFile + "; " + SLEEP_PAST_TIMEOUT);

      // then
      assertThat(survivorOf(pidFile)).isEmpty();
    }

    @Test
    @DisplayName("자식 없이 도는 명령도 끝낸다")
    void timeoutKillsCommandItself() throws IOException {
      // given
      Path pidFile = dir.resolve("busy.pid");

      // when
      runPastTimeout("echo $$ > " + pidFile + "; while :; do :; done");

      // then
      assertThat(survivorOf(pidFile)).isEmpty();
    }

    @Test
    @DisplayName("stdout을 쥔 자식이 남아도 시간 초과로 끝낸다")
    void backgroundHolderOfStdoutTimesOut() {
      // when·then
      assertThatThrownBy(() -> commands.resolve(SLEEP_PAST_TIMEOUT + " & echo " + SECRET))
          .isInstanceOf(CallFailureException.class)
          .hasMessageContaining("timed out");
    }
  }

  @Nested
  @DisplayName("출력이 상한(1 MiB)을 넘으면")
  class OutputOverLimit {
    @Test
    @DisplayName("거부한다")
    void rejectsOversizedOutput() {
      // when·then
      assertThatThrownBy(() -> patient.resolve(OVERSIZED_OUTPUT))
          .isInstanceOf(CallFailureException.class)
          .hasMessageContaining("exceeds");
    }

    @Test
    @DisplayName("명령이 끝나기를 기다리지 않고 거부한다")
    void rejectsOversizedOutputWithoutWaitingForExit() {
      // when·then
      assertThatThrownBy(() -> patient.resolve(OVERSIZED_OUTPUT_IGNORING_SIGPIPE))
          .isInstanceOf(CallFailureException.class)
          .hasMessageContaining("exceeds");
    }
  }

  @Nested
  @DisplayName("출력 크기가 상한 경계에 있으면")
  class OutputAtLimitBoundary {
    @Test
    @DisplayName("상한과 같은 출력은 받아들인다")
    void acceptsOutputAtLimit() {
      // when
      String resolved = patient.resolve(outputOf(ONE_MIB));

      // then
      assertThat(resolved).hasSize(1_048_576);
    }

    @Test
    @DisplayName("상한보다 1바이트 많은 출력은 거부한다")
    void rejectsOutputOneByteOverLimit() {
      // when·then
      assertThatThrownBy(() -> patient.resolve(outputOf(ONE_MIB + 1)))
          .isInstanceOf(CallFailureException.class)
          .hasMessageContaining("exceeds");
    }
  }

  @Nested
  @DisplayName("실행 중 부른 스레드가 중단되면")
  class CallerInterrupted {
    @Test
    @DisplayName("cancelled로 거부한다")
    void interruptedCallIsCancelled() throws Exception {
      // when
      Interrupted call = interruptWhileRunning();

      // then
      assertThat(call.failure().failure().kind()).isEqualTo(CallFailure.Kind.CANCELLED);
    }

    @Test
    @DisplayName("명령 프로세스를 끝낸다")
    void interruptKillsCommand() throws Exception {
      // when
      Interrupted call = interruptWhileRunning();

      // then
      assertThat(survivorOf(call.pidFile())).isEmpty();
    }

    @Test
    @DisplayName("부른 스레드의 인터럽트 표시를 보존한다")
    void interruptFlagSurvives() throws Exception {
      // when
      Interrupted call = interruptWhileRunning();

      // then
      assertThat(call.caller().wasInterruptedAtEnd()).isTrue();
    }
  }
}
