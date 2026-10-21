package io.scos.collection.internal;

/** 커서가 보존 하한 아래다 — 소비자는 전량 재동기화해야 한다(TS SourceChangeCursorExpiredError). */
public final class ChangeCursorExpiredException extends RuntimeException {
  private final long retainedAfter;
  private final long head;

  public ChangeCursorExpiredException(long retainedAfter, long head) {
    super(
        "Source change cursor expired; resynchronization required (retainedAfter="
            + retainedAfter
            + ", head="
            + head
            + ")");
    this.retainedAfter = retainedAfter;
    this.head = head;
  }

  public long retainedAfter() {
    return retainedAfter;
  }

  public long head() {
    return head;
  }
}
