package io.scos.engine.support.text;

import java.util.stream.IntStream;

/**
 * WHATWG Encoding Standard의 UTF-8 디코드(TS `new TextDecoder().decode(bytes)`와 같다): 앞 BOM 하나를 떼고, 잘못된
 * 시퀀스는 "가장 긴 유효 부분" 규칙으로 U+FFFD 하나씩 바꾼다. Java `new String(bytes, UTF_8)`은 대체 문자 수가 다르다 — 예: UTF-8로
 * 쓴 서로게이트 `ED A0 80`은 WHATWG에서 U+FFFD 셋, Java에서 하나다. 원본 본문을 TS와 같은 문자열로 읽어야 할 때(해시·저장 값) 쓴다.
 */
public final class Utf8 {
  private static final int REPLACEMENT = 0xFFFD;
  private static final int BOM_LENGTH = 3;

  private Utf8() {}

  public static String decodeLikeTextDecoder(byte[] bytes) {
    Decoder decoder = new Decoder(bytes.length);
    int start = hasBom(bytes) ? BOM_LENGTH : 0;
    IntStream.range(start, bytes.length).forEach(i -> decoder.feed(bytes[i] & 0xFF));
    return decoder.finish();
  }

  private static boolean hasBom(byte[] bytes) {
    return bytes.length >= BOM_LENGTH
        && (bytes[0] & 0xFF) == 0xEF
        && (bytes[1] & 0xFF) == 0xBB
        && (bytes[2] & 0xFF) == 0xBF;
  }

  /** WHATWG UTF-8 디코더 상태(남은 연속 바이트 수·지금까지의 코드 포인트·다음 연속 바이트의 허용 범위). */
  private static final class Decoder {
    private static final int CONTINUATION_MIN = 0x80;
    private static final int CONTINUATION_MAX = 0xBF;

    private final StringBuilder out;
    private int needed;
    private int seen;
    private int codePoint;
    private int lower = CONTINUATION_MIN;
    private int upper = CONTINUATION_MAX;

    Decoder(int capacity) {
      out = new StringBuilder(capacity);
    }

    void feed(int b) {
      if (needed == 0) {
        lead(b);
        return;
      }
      if (b < lower || b > upper) {
        // 끊긴 시퀀스는 대체 문자 하나, 이 바이트는 새 시퀀스의 시작으로 다시 본다
        reset();
        out.appendCodePoint(REPLACEMENT);
        lead(b);
        return;
      }
      lower = CONTINUATION_MIN;
      upper = CONTINUATION_MAX;
      codePoint = (codePoint << 6) | (b & 0x3F);
      seen++;
      if (seen == needed) {
        out.appendCodePoint(codePoint);
        reset();
      }
    }

    private void lead(int b) {
      if (b <= 0x7F) out.append((char) b);
      else if (b >= 0xC2 && b <= 0xDF) start(1, b & 0x1F);
      else if (b >= 0xE0 && b <= 0xEF) {
        if (b == 0xE0) lower = 0xA0;
        if (b == 0xED) upper = 0x9F;
        start(2, b & 0x0F);
      } else if (b >= 0xF0 && b <= 0xF4) {
        if (b == 0xF0) lower = 0x90;
        if (b == 0xF4) upper = 0x8F;
        start(3, b & 0x07);
      } else out.appendCodePoint(REPLACEMENT);
    }

    private void start(int continuations, int bits) {
      needed = continuations;
      codePoint = bits;
    }

    private void reset() {
      needed = 0;
      seen = 0;
      codePoint = 0;
      lower = CONTINUATION_MIN;
      upper = CONTINUATION_MAX;
    }

    String finish() {
      if (needed != 0) out.appendCodePoint(REPLACEMENT);
      return out.toString();
    }
  }
}
