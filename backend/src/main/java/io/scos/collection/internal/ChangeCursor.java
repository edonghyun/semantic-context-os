package io.scos.collection.internal;

/**
 * F10 읽기 커서 규칙(TS validateChangeCursor): 0 ≤ after ≤ head, 1 ≤ limit ≤ 1000, after < 보존 하한이면 만료.
 */
public final class ChangeCursor {
  public static final int MAX_LIMIT = 1000;

  private ChangeCursor() {}

  public static void validate(long after, int limit, long floor, long head) {
    if (after < 0 || after > head)
      throw new IllegalArgumentException(
          "after must be in [0, head]: after=" + after + " head=" + head);
    if (limit < 1 || limit > MAX_LIMIT)
      throw new IllegalArgumentException("limit must be in [1, " + MAX_LIMIT + "]: " + limit);
    if (after < floor) throw new ChangeCursorExpiredException(floor, head);
  }
}
