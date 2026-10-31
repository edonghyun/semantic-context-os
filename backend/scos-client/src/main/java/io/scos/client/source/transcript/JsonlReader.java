package io.scos.client.source.transcript;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** 완전한 JSONL 줄만 버퍼 단위로 읽는다. 채널의 수명은 호출자가 소유한다. */
final class JsonlReader implements Iterator<JsonlReader.Line> {
  private static final int BUFFER_BYTES = 1 << 16;
  private final FileChannel channel;
  private final ByteBuffer buffer = ByteBuffer.allocate(BUFFER_BYTES);
  private long endOffset;
  private long number;
  private @Nullable Line next;
  private boolean exhausted;

  // 줄은 읽기 경계의 전달 값이며 배열의 값 동등성을 사용하지 않는다.
  @SuppressWarnings("ArrayRecordComponent")
  record Line(byte[] bytes, long endOffset, long number) {}

  /** Iterator가 표현하지 못하는 읽기 실패. sink가 던진 예외와 구분해 파일만 건너뛴다. */
  static final class ReadFailure extends UncheckedIOException {
    ReadFailure(IOException cause) {
      super(cause);
    }
  }

  JsonlReader(FileChannel channel, long offset, long number) throws IOException {
    this.channel = channel;
    this.endOffset = offset;
    this.number = number;
    channel.position(offset);
    buffer.limit(0);
  }

  @Override
  public boolean hasNext() {
    if (next != null) return true;
    if (exhausted) return false;
    try {
      next = readLine();
      exhausted = next == null;
      return !exhausted;
    } catch (IOException e) {
      throw new ReadFailure(e);
    }
  }

  @Override
  public Line next() {
    if (!hasNext()) throw new NoSuchElementException();
    Line result = next;
    next = null;
    // hasNext()가 true이면 완전한 줄이 준비되어 있다.
    return Objects.requireNonNull(result);
  }

  private @Nullable Line readLine() throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    while (true) {
      if (!buffer.hasRemaining()) {
        buffer.clear();
        int read = channel.read(buffer);
        buffer.flip();
        if (read < 0) return null; // 개행이 없는 마지막 조각은 다음 수집 때 다시 읽는다.
      }
      int start = buffer.position();
      int end = start;
      while (end < buffer.limit() && buffer.array()[end] != '\n') end++;
      bytes.write(buffer.array(), start, end - start);
      boolean complete = end < buffer.limit();
      buffer.position(complete ? end + 1 : end);
      endOffset += buffer.position() - start;
      if (complete) return new Line(bytes.toByteArray(), endOffset, number++);
    }
  }
}
