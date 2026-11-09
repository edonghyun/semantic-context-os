package io.scos.engine.admin;

import io.scos.spi.access.AdminStore;
import java.util.function.Function;

/** 실제 저장소의 트랜잭션을 유지하며 다음 커밋의 결과나 응답만 실패시킨다. */
public final class CommitFailureStore implements AdminStore {
  private enum Outcome {
    ROLLBACK,
    RESPONSE_LOST,
    CONNECTION_LOST
  }

  private final AdminStore delegate;
  private final Outcome outcome;
  private boolean failed;

  private CommitFailureStore(AdminStore delegate, Outcome outcome) {
    this.delegate = delegate;
    this.outcome = outcome;
  }

  public static CommitFailureStore rollbackNext(AdminStore store) {
    return new CommitFailureStore(store, Outcome.ROLLBACK);
  }

  public static CommitFailureStore loseNextResponse(AdminStore store) {
    return new CommitFailureStore(store, Outcome.RESPONSE_LOST);
  }

  public static CommitFailureStore loseResponseAndConnection(AdminStore store) {
    return new CommitFailureStore(store, Outcome.CONNECTION_LOST);
  }

  @Override
  public <T> T transact(Function<Session, T> work) {
    if (failed) {
      if (outcome == Outcome.CONNECTION_LOST)
        throw new IllegalStateException("database unavailable");
      return delegate.transact(work);
    }
    failed = true;
    if (outcome == Outcome.ROLLBACK)
      return delegate.transact(
          session -> {
            var ignored = work.apply(session);
            throw new IllegalStateException("confirmed rollback");
          });
    delegate.transact(work);
    throw new IllegalStateException("lost commit response");
  }
}
