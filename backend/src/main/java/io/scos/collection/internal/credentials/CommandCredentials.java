package io.scos.collection.internal.credentials;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * `command:<셸 명령>` — `/bin/sh -c` 실행 stdout의 앞뒤 공백을 뗀 값(TS CommandCredentialResolver, ADR-0022 §2).
 *
 * <ul>
 *   <li>시간 상한(기본 10초)은 프로세스 종료와 stdout 닫힘을 합친 시간이다. 넘기면 명령과 그 자식 프로세스를 끝내고 거부한다.
 *   <li>0이 아닌 종료는 종료 코드만 담아 거부한다. stdout·stderr 내용과 원인 예외는 오류에 싣지 않는다. stderr는 버린다.
 *   <li>stdin은 비어 있다(`/dev/null`). stdout은 {@link #MAX_OUTPUT_BYTES}까지만 읽는다.
 * </ul>
 */
public final class CommandCredentials implements CredentialScheme {
  /** TS 기본값과 같다(`timeoutMs ?? 10_000`). */
  public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

  /** TS(Node execFile)의 기본 maxBuffer와 같은 1 MiB. */
  static final int MAX_OUTPUT_BYTES = 1024 * 1024;

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
    FutureTask<byte[]> stdout = new FutureTask<>(() -> readCapped(process.getInputStream()));
    Thread.ofVirtual().name("credential-command-stdout").start(stdout);
    long deadline = System.nanoTime() + timeout.toNanos();
    byte[] output;
    try {
      // 출력부터 본다: 상한을 넘었으면 명령이 끝나기(SIGPIPE를 무시하면 끝나지 않는다)나 시간 상한을 기다리지 않고 끊는다
      output = stdout.get(remaining(deadline), TimeUnit.NANOSECONDS);
      if (output.length > MAX_OUTPUT_BYTES)
        throw aborted(
            process,
            CredentialFailures.invalid(
                "credential command output exceeds " + MAX_OUTPUT_BYTES + " bytes"));
      if (!process.waitFor(remaining(deadline), TimeUnit.NANOSECONDS)) throw timedOut(process);
    } catch (TimeoutException e) {
      throw timedOut(process);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw aborted(process, CredentialFailures.cancelled("credential command interrupted"));
    } catch (ExecutionException e) {
      throw aborted(
          process, CredentialFailures.invalid("credential command output could not be read"));
    }
    if (process.exitValue() != 0)
      throw CredentialFailures.invalid(
          "credential command failed with exit code " + process.exitValue());
    String value = CredentialText.trim(new String(output, StandardCharsets.UTF_8));
    if (value.isEmpty())
      throw CredentialFailures.invalid("credential command produced empty output");
    return value;
  }

  private static Process start(String command) {
    try {
      return new ProcessBuilder(List.of("/bin/sh", "-c", command))
          .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))
          .redirectError(ProcessBuilder.Redirect.DISCARD)
          .start();
    } catch (IOException e) {
      throw CredentialFailures.invalid("credential command could not be started");
    }
  }

  /** 상한 +1바이트까지 읽는다. 넘쳤는지는 길이로 판단하고, 나머지는 읽지 않는다. */
  private static byte[] readCapped(InputStream in) throws IOException {
    try (in) {
      return in.readNBytes(MAX_OUTPUT_BYTES + 1);
    }
  }

  private static long remaining(long deadline) {
    return Math.max(0, deadline - System.nanoTime());
  }

  private static RuntimeException timedOut(Process process) {
    return aborted(process, CredentialFailures.invalid("credential command timed out"));
  }

  /** 명령과 그 자손을 끝내고 실패를 돌려준다. */
  private static RuntimeException aborted(Process process, RuntimeException failure) {
    destroyTree(process);
    return failure;
  }

  /**
   * 자식부터 끝낸다 — 부모가 먼저 끝나면 자식은 다른 부모로 넘어가 더는 자손으로 보이지 않는다. 목록을 읽은 뒤 갓 띄운 자식·이중 fork 손자는 끝내지
   * 못한다(ADR-0022 §2 한계). 부모 종료 뒤 한 번 더 보는 것도 검토했지만, 테스트로 구별할 수 없는 창 좁히기라 두지 않는다.
   */
  private static void destroyTree(Process process) {
    process.descendants().forEach(ProcessHandle::destroyForcibly);
    process.destroyForcibly();
  }
}
