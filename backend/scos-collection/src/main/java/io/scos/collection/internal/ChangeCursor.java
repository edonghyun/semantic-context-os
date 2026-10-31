package io.scos.collection.internal;

/** F10 읽기 커서: seq > after를 limit개까지. 규칙은 0 ≤ after ≤ head, 1 ≤ limit ≤ 1000, after < 보존 하한이면 만료. */
public record ChangeCursor(long after, int limit) {
  public static final int MAX_LIMIT = 1000;

  /** 보존 구간 [floor, head] 안에서 읽을 수 있는 커서인지 확인한다. */
  public void checkWithin(long floor, long head) {
    if (after < 0 || after > head)
      throw new IllegalArgumentException(
          "after must be in [0, head]: after=" + after + " head=" + head);
    if (limit < 1 || limit > MAX_LIMIT)
      throw new IllegalArgumentException("limit must be in [1, " + MAX_LIMIT + "]: " + limit);
    if (after < floor) throw new ChangeCursorExpiredException(floor, head);
  }
}
