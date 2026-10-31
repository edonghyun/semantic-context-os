package io.scos.mcp;

import io.scos.support.Eventually;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** SDK와 독립적으로 요청·응답 바이트와 EOF 배리어를 관찰한다. 실패한 작업도 테스트 JVM을 붙잡지 않는다. */
final class InFlightScenario implements AutoCloseable {
  private static final Duration WAIT = Duration.ofSeconds(2);
  private static final Duration DRAIN = Duration.ofSeconds(5);

  /** notifyAll 뒤 읽기 스레드가 다시 실행되어도 EOF가 보류되는지 관찰하는 창. */
  private static final Duration PENDING_OBSERVATION = Duration.ofMillis(200);

  private final CountDownLatch eof = new CountDownLatch(1);
  private final InFlightRequests requests = new InFlightRequests(DRAIN, eof);
  private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
  private final OutputStream output = requests.writing(new BufferedOutputStream(bytes));
  private final ExecutorService workers =
      Executors.newCachedThreadPool(Thread.ofPlatform().daemon().factory());
  private InputStream input;
  private Future<Integer> drained;
  private volatile Thread reader;

  void givenRequests(String text) throws Exception {
    byte[] wire = text.getBytes(StandardCharsets.UTF_8);
    input = requests.reading(new ByteArrayInputStream(wire));
    byte[] read = new byte[wire.length];
    int count = input.read(read, 0, read.length);
    if (count != wire.length || !text.equals(new String(read, StandardCharsets.UTF_8)))
      throw new IllegalStateException("given 준비 실패: 요청 바이트 보존");
  }

  void givenBytewiseRequests(String text) throws Exception {
    input = requests.reading(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    for (int expected : text.getBytes(StandardCharsets.UTF_8)) {
      int actual = input.read();
      if (actual != (expected & 0xff)) throw new AssertionError("입력 바이트가 바뀌었다: " + actual);
    }
  }

  int readEmptyBufferBefore(String text) throws Exception {
    input = requests.reading(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    return input.read(new byte[0], 0, 0);
  }

  int readFirstByte(int value) throws Exception {
    input = requests.reading(new ByteArrayInputStream(new byte[] {(byte) value}));
    return input.read();
  }

  void givenDeferredEof(String text) throws Exception {
    givenRequests(text);
    startEof();
    if (readEof() != -1 || signalled())
      throw new IllegalStateException("given 준비 실패: 마지막 요청을 넘기는 첫 EOF");
    startEof();
    awaitDraining();
  }

  void startEof() {
    reader = null;
    drained =
        workers.submit(
            () -> {
              reader = Thread.currentThread();
              return input.read();
            });
  }

  void awaitDraining() {
    Eventually.await(
        "요청 응답을 기다리는 EOF",
        () ->
            !drained.isDone() && reader != null && reader.getState() == Thread.State.TIMED_WAITING,
        WAIT);
  }

  void respond(String text) throws Exception {
    Future<?> written =
        workers.submit(
            () -> {
              output.write(text.getBytes(StandardCharsets.UTF_8));
              return null;
            });
    written.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
  }

  int readEof() throws Exception {
    return drained.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
  }

  boolean eofRemainsPending() throws Exception {
    try {
      drained.get(PENDING_OBSERVATION.toMillis(), TimeUnit.MILLISECONDS);
      return false;
    } catch (TimeoutException expected) {
      return !signalled();
    }
  }

  void respondBytewise(String text) throws Exception {
    Future<?> written =
        workers.submit(
            () -> {
              for (byte b : text.getBytes(StandardCharsets.UTF_8)) output.write(b);
              return null;
            });
    written.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
  }

  String written() {
    return bytes.toString(StandardCharsets.UTF_8);
  }

  boolean interruptedReadPreservesFlag() throws Exception {
    Future<Boolean> result =
        workers.submit(
            () -> {
              Thread.currentThread().interrupt();
              try {
                input.read();
                throw new AssertionError("인터럽트된 응답 대기가 성공했다");
              } catch (IOException e) {
                if (!e.getMessage().contains("응답 대기 중 중단")) throw e;
                return Thread.currentThread().isInterrupted();
              }
            });
    return result.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
  }

  boolean signalled() {
    return eof.getCount() == 0;
  }

  @Override
  public void close() {
    workers.shutdownNow();
  }
}
