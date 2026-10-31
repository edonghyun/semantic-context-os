package io.scos.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

final class StdioLines {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** stdin 줄을 테스트가 차례로 넣는 입력. 끝을 알리면 EOF. */
  static final class LineInput extends InputStream {
    private final BlockingQueue<byte[]> chunks = new LinkedBlockingQueue<>();
    private byte[] current = new byte[0];
    private int position;
    private boolean ended;

    void send(String line) {
      chunks.add((line + "\n").getBytes(StandardCharsets.UTF_8));
    }

    void end() {
      chunks.add(new byte[0]);
    }

    @Override
    public int read() throws IOException {
      byte[] one = new byte[1];
      return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      if (len == 0) return 0;
      while (position >= current.length) {
        if (ended) return -1;
        try {
          current = chunks.take();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IOException(e);
        }
        position = 0;
        // send는 빈 조각을 넣지 않는다: 빈 조각은 끝 표시다
        if (current.length == 0) ended = true;
      }
      int n = Math.min(len, current.length - position);
      System.arraycopy(current, position, b, off, n);
      position += n;
      return n;
    }
  }

  /** stdout을 줄 단위로 모은다. */
  static final class LineOutput extends OutputStream {
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
    private final BlockingQueue<String> arrived = new LinkedBlockingQueue<>();
    private final List<String> all = new ArrayList<>();

    @Override
    public synchronized void write(int b) {
      if (b != '\n') {
        pending.write(b);
        return;
      }
      String line = pending.toString(StandardCharsets.UTF_8);
      pending.reset();
      all.add(line);
      arrived.add(line);
    }

    synchronized List<String> lines() {
      List<String> copy = new ArrayList<>(all);
      if (pending.size() > 0) copy.add(pending.toString(StandardCharsets.UTF_8));
      return copy;
    }

    /** id가 같은 응답을 상한 안에 기다린다. 없으면 missing 노드. */
    JsonNode response(int id, Duration wait) throws InterruptedException {
      long deadline = System.nanoTime() + wait.toNanos();
      for (long left = wait.toNanos(); left > 0; left = deadline - System.nanoTime()) {
        String line = arrived.poll(left, TimeUnit.NANOSECONDS);
        if (line == null) break;
        JsonNode message = JSON.readTree(line);
        if (message.path("id").asInt(-1) == id) return message;
      }
      return JSON.missingNode();
    }
  }
}
