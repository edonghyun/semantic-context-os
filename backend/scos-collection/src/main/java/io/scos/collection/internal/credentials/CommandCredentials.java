package io.scos.collection.internal.credentials;

import io.scos.spi.connector.CallFailureException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * `command:<셸 명령>` — `/bin/sh -c` 실행 stdout의 앞뒤 공백을 뗀 값(ADR-0022 §2). 부모의 종료 코드가 0이고 출력이 있으면 성공한다.
 * 시간·출력 상한과 무관하게 모든 경로에서 자손을 정리한다. 명령의 stdin·stderr는 /dev/null이며, 오류에는 출력이나 원인 예외를 담지 않는다.
 */
public final class CommandCredentials implements CredentialScheme {
  public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

  /** stdout 상한 1 MiB. */
  static final int MAX_OUTPUT_BYTES = 1024 * 1024;

  /**
   * 보호자의 monitor mode가 원래 /bin/sh 명령을 별도 프로세스 그룹에 둔다. 사용자 명령의 exec나 EXIT trap과 독립적으로 자손을 끝내며, 부모를
   * 잃은 자식도 그 그룹으로 찾는다. 종료 코드는 별도 파이프로 보내고 Java의 출력 읽기·정리 승인을 기다린다.
   */
  private static final String SHELL =
      """
      exec 3>&2 2>/dev/null
      set -m
      scos_command=
      scos_cleanup() {
        if [ -n "$scos_command" ]; then
          kill -KILL -- "-$scos_command" 2>/dev/null
          wait "$scos_command" 2>/dev/null
        fi
      }
      trap scos_cleanup EXIT
      trap 'exit 143' TERM HUP INT
      /bin/sh -c "$1" </dev/null 2>/dev/null 3>&- &
      scos_command=$!
      wait "$scos_command"
      scos_status=$?
      printf "%s\\n" "$scos_status" >&3
      IFS= read -r scos_release
      exit "$scos_status"
      """;

  private final Duration timeout;

  public CommandCredentials(Duration timeout) {
    this.timeout = timeout;
  }

  @Override
  public String name() {
    return "command";
  }

  @Override
  public String resolve(String reference) {
    Process process = start(reference);
    long deadline = System.nanoTime() + timeout.toNanos();
    try {
      // 읽기는 프로세스 정리로 끝난다. 호출자의 deadline 밖에서 executor 종료를 기다리지 않는다.
      var stdout =
          CompletableFuture.supplyAsync(
              () -> readCapped(process.getInputStream()), Thread::startVirtualThread);
      var exit = CompletableFuture.supplyAsync(() -> readExit(process), Thread::startVirtualThread);
      return completedOutput(process, stdout, exit, deadline);
    } catch (TimeoutException e) {
      throw CredentialFailures.invalid("credential command timed out");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw CredentialFailures.cancelled("credential command interrupted");
    } catch (ExecutionException | IOException e) {
      if (e.getCause() instanceof CallFailureException failure) throw failure;
      throw CredentialFailures.invalid("credential command output could not be read");
    } finally {
      destroyTree(process);
    }
  }

  private static String completedOutput(
      Process process,
      CompletableFuture<byte[]> stdout,
      CompletableFuture<Integer> exit,
      long deadline)
      throws ExecutionException, InterruptedException, TimeoutException, IOException {
    CompletableFuture.anyOf(stdout, exit).get(remaining(deadline), TimeUnit.NANOSECONDS);
    int status = exit.get(remaining(deadline), TimeUnit.NANOSECONDS);
    process.descendants().forEach(ProcessHandle::destroyForcibly);
    process.getOutputStream().close();
    if (!process.waitFor(remaining(deadline), TimeUnit.NANOSECONDS)) throw new TimeoutException();
    byte[] output = stdout.get(remaining(deadline), TimeUnit.NANOSECONDS);
    if (status != 0)
      throw CredentialFailures.invalid("credential command failed with exit code " + status);
    String value = CredentialText.trim(new String(output, StandardCharsets.UTF_8));
    if (value.isEmpty())
      throw CredentialFailures.invalid("credential command produced empty output");
    return value;
  }

  private static void requireSize(byte[] output) {
    if (output.length > MAX_OUTPUT_BYTES)
      throw CredentialFailures.invalid(
          "credential command output exceeds " + MAX_OUTPUT_BYTES + " bytes");
  }

  private static Process start(String command) {
    try {
      return new ProcessBuilder(List.of("/bin/bash", "-c", SHELL, "scos-credential", command))
          .start();
    } catch (IOException e) {
      throw CredentialFailures.invalid("credential command could not be started");
    }
  }

  private static int readExit(Process process) {
    try (var reader =
        new BufferedReader(
            new InputStreamReader(process.getErrorStream(), StandardCharsets.US_ASCII))) {
      String status = reader.readLine();
      if (status == null)
        throw CredentialFailures.invalid("credential command ended without completion status");
      return Integer.parseInt(status);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** 상한 +1바이트까지 읽는다. 넘친 출력은 프로세스 종료를 기다리지 않고 거부한다. */
  private static byte[] readCapped(InputStream in) {
    try (in) {
      byte[] output = in.readNBytes(MAX_OUTPUT_BYTES + 1);
      requireSize(output);
      return output;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static long remaining(long deadline) {
    return Math.max(0, deadline - System.nanoTime());
  }

  /** 부모가 자손을 회수할 기회를 준 뒤 강제 종료한다. 이중 fork로 이미 분리된 프로세스는 대상 밖이다. */
  private static void destroyTree(Process process) {
    boolean interrupted = Thread.interrupted();
    try {
      process.descendants().forEach(ProcessHandle::destroyForcibly);
      process.destroy();
      process.getOutputStream().close();
      process.waitFor(100, TimeUnit.MILLISECONDS);
    } catch (IOException e) {
      // 명령이 파이프를 먼저 닫았어도 부모 종료는 아래에서 보장한다.
    } catch (InterruptedException e) {
      interrupted = true;
    } finally {
      process.destroyForcibly();
      if (interrupted) Thread.currentThread().interrupt();
    }
  }
}
