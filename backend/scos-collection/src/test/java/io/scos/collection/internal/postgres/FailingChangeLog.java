package io.scos.collection.internal.postgres;

import io.scos.collection.internal.SourceChangeLog;
import io.scos.spi.connector.SourceInstanceId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 트랜잭션의 마지막 쓰기(변경 로그 append, ADR-0021)에서 실패하는 변경 로그. 행을 실제로 쓴 뒤 던진다 — 같은 트랜잭션이 롤백되면 그 행도 남지 않아야 한다.
 */
final class FailingChangeLog implements SourceChangeLog {
  static final String MESSAGE = "change log append failed";
  private final SourceChangeLog delegate;

  FailingChangeLog(SourceChangeLog delegate) {
    this.delegate = delegate;
  }

  @Override
  public void appendAll(List<Entry> entries) {
    delegate.appendAll(entries);
    throw new IllegalStateException(MESSAGE);
  }

  @Override
  public Page read(Optional<SourceInstanceId> source, long after, int limit) {
    return delegate.read(source, after, limit);
  }

  @Override
  public long head() {
    return delegate.head();
  }

  @Override
  public int pruneBefore(Instant cutoff) {
    return delegate.pruneBefore(cutoff);
  }
}
