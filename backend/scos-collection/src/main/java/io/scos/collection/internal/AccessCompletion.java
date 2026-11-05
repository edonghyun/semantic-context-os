package io.scos.collection.internal;

import io.scos.engine.EventLog;
import io.scos.spi.connector.SourceDescriptor;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 접근 병합의 실제 적용 모드와 실패를 본문 마감에서 격리한다. 트랜잭션과 전체성 fallback은 관측 저장소가 소유한다. */
public final class AccessCompletion {
  private static final Logger log = LoggerFactory.getLogger(AccessCompletion.class);
  private final CollectionStores stores;
  private final Clock clock;
  private final WriteFence fence;

  public AccessCompletion(CollectionStores stores, Clock clock) {
    this.stores = stores;
    this.fence = new WriteFence(stores);
    this.clock = clock;
  }

  public AccessResult finish(
      WorkClaim claim,
      RunId run,
      SourceDescriptor source,
      AccessCollection.Result collected,
      Instant at) {
    if (collected.result() != AccessResult.COMPLETE
        && collected.result() != AccessResult.INCOMPLETE) return collected.result();
    try {
      AccessMerge applied =
          stores
              .accessObservations()
              .merge(
                  run,
                  source.id(),
                  collected.directory(),
                  at,
                  () -> fence.requireWritable(claim, run, Optional.of(source), clock.instant()));
      if (collected.directory() == AccessMerge.COMPLETE && applied == AccessMerge.INCOMPLETE)
        EventLog.warn(log, "collection.access.restricted")
            .source(source.id())
            .run(run.value())
            .log();
      return applied == AccessMerge.COMPLETE ? collected.result() : AccessResult.INCOMPLETE;
    } catch (ClaimLostException lost) {
      return AccessResult.CLAIM_LOST;
    } catch (RuntimeException incomplete) {
      EventLog.warn(log, "collection.access.incomplete")
          .source(source.id())
          .run(run.value())
          .cause(incomplete)
          .log();
      return AccessResult.INCOMPLETE;
    }
  }
}
