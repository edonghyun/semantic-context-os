package io.scos.mcp;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * stdio에서 읽은 요청(id·method가 있는 줄)의 id를 모으고 쓴 응답(id만 있는 줄)의 id를 지워, 바닥 stdin의 EOF를 응답이 다 나갈 때까지(최대
 * drainLimit) SDK에 넘기지 않는다. SDK 전송은 EOF를 읽는 순간 닫힘 상태가 되어 처리 중인 응답을 버리기 때문이다(mcp-core 2.0.1
 * StdioServerTransportProvider: 입력 finally의 isClosing → 출력 handle이 버림). EOF를 넘기는 순간 {@code eof}를
 * 내린다.
 */
final class InFlightRequests {
  private static final ObjectMapper JSON = new ObjectMapper();

  private final Duration drainLimit;
  private final CountDownLatch eof;
  private final Set<String> pending = new HashSet<>();

  InFlightRequests(Duration drainLimit, CountDownLatch eof) {
    this.drainLimit = drainLimit;
    this.eof = eof;
  }

  InputStream reading(InputStream in) {
    return new Requests(in);
  }

  OutputStream writing(OutputStream out) {
    return new Responses(out);
  }

  private synchronized void requestRead(JsonNode id) {
    pending.add(id.toString());
  }

  private synchronized void responseWritten(JsonNode id) {
    pending.remove(id.toString());
    notifyAll();
  }

  /** 응답이 다 나가거나 drainLimit이 지날 때까지 기다린다. */
  private synchronized void awaitDrained() throws IOException {
    long deadline = System.nanoTime() + drainLimit.toNanos();
    try {
      for (long left = drainLimit.toNanos(); !pending.isEmpty() && left > 0; ) {
        wait(Math.max(1, left / 1_000_000));
        left = deadline - System.nanoTime();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("응답 대기 중 중단", e);
    }
    if (!pending.isEmpty())
      System.err.println(
          "MCP stdin EOF: 응답 " + pending.size() + "건이 " + drainLimit + " 안에 나오지 않아 버리고 닫는다");
  }

  /** 한 줄 = JSON-RPC 메시지 하나(SDK와 같이 \n·\r 구분). 파싱 실패는 SDK가 세션을 끝내므로 세지 않는다. */
  private static @Nullable JsonNode message(ByteArrayOutputStream line) {
    String text = line.toString(StandardCharsets.UTF_8).strip();
    line.reset();
    if (text.isEmpty()) return null;
    try {
      JsonNode node = JSON.readTree(text);
      return node.isObject() ? node : null;
    } catch (JacksonException e) {
      return null;
    }
  }

  private static boolean hasId(JsonNode m) {
    return m.hasNonNull("id");
  }

  private final class Requests extends FilterInputStream {
    private final ByteArrayOutputStream line = new ByteArrayOutputStream();

    /** 마지막으로 받은 바이트가 요청 줄을 끝낸 \r이다(SDK가 \n을 엿보는 중). */
    private boolean crEndedRequest;

    Requests(InputStream in) {
      super(in);
    }

    @Override
    public int read() throws IOException {
      int b = super.read();
      if (b < 0) return endOfInput();
      take((byte) b);
      return b;
    }

    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
      int n = super.read(buf, off, len);
      if (n < 0) return endOfInput();
      for (int i = off; i < off + n; i++) take(buf[i]);
      return n;
    }

    // 요청은 SDK가 바이트를 받기 전에 센다 — 응답이 세기보다 먼저 나갈 수 없다
    private void take(byte b) {
      boolean ended = b == '\n' || b == '\r';
      boolean request = ended && lineEnded();
      crEndedRequest = b == '\r' && request;
      if (!ended) line.write(b);
    }

    /** 끝난 줄이 요청이면 세고 true. */
    private boolean lineEnded() {
      JsonNode m = message(line);
      if (m == null || !hasId(m) || !m.hasNonNull("method")) return false;
      requestRead(m.get("id"));
      return true;
    }

    // EOF 시점에 막 끝난 요청 줄(\n 없는 마지막 줄, 또는 \r 뒤 \n을 엿보는 중)은 SDK가 이 -1을 받아야 넘긴다 — 여기서 기다리면
    // 입력 스레드가 자기 요청의 응답을 기다린다. 그 줄은 세고 -1만 돌려준 뒤 다음 EOF에서 기다린다. 요청이 아닌 줄(공백·깨진 줄·알림)은
    // SDK가 거부하고 읽기를 멈출 수 있어 다음 EOF가 오지 않는다 — 미루지 않고 지금 기다린다.
    private int endOfInput() throws IOException {
      boolean requestJustEnded = line.size() > 0 ? lineEnded() : crEndedRequest;
      crEndedRequest = false;
      if (requestJustEnded) return -1;
      awaitDrained();
      eof.countDown();
      return -1;
    }
  }

  private final class Responses extends FilterOutputStream {
    private final ByteArrayOutputStream line = new ByteArrayOutputStream();

    Responses(OutputStream out) {
      super(out);
    }

    @Override
    public void write(int b) throws IOException {
      out.write(b);
      take((byte) b);
    }

    @Override
    public void write(byte[] buf, int off, int len) throws IOException {
      out.write(buf, off, len);
      for (int i = off; i < off + len; i++) take(buf[i]);
    }

    // 응답은 바닥 스트림에 flush한 뒤에 센다 — EOF를 넘긴 뒤 JVM이 끝나도 응답은 이미 나갔다
    private void take(byte b) throws IOException {
      if (b != '\n') {
        line.write(b);
        return;
      }
      JsonNode m = message(line);
      if (m != null && hasId(m) && !m.has("method")) {
        out.flush();
        responseWritten(m.get("id"));
      }
    }
  }
}
