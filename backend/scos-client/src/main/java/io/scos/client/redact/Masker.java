package io.scos.client.redact;

/** 원문을 앞에서부터 복사하며 고른 구간만 {@link Redaction#MASK}로 바꾼다. 구간은 겹치지 않고 왼쪽부터 주어진다. */
final class Masker {
  private final String text;
  private final StringBuilder out;
  private int copied;

  Masker(String text) {
    this.text = text;
    this.out = new StringBuilder(text.length());
  }

  /** {@code [start, end)}를 가린다. 이미 지나간 자리나 빈 구간은 건너뛴다. */
  void mask(int start, int end) {
    if (start < copied || end <= start) {
      return;
    }
    out.append(text, copied, start).append(Redaction.MASK);
    copied = end;
  }

  /** 가린 것이 없으면 원문 인스턴스 그대로다. */
  String result() {
    if (copied == 0 && out.isEmpty()) {
      return text;
    }
    return out.append(text, copied, text.length()).toString();
  }
}
