package io.scos.collection.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.collection.internal.memory.MemoryCheckpointPort;
import io.scos.collection.internal.memory.MemoryRunStore;
import io.scos.collection.internal.memory.MemorySourceInstanceStore;
import io.scos.collection.internal.memory.MemoryWorkClaimPort;
import io.scos.engine.support.text.MutableClock;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.connector.CollectionPage;
import io.scos.spi.connector.SourceAdapter;
import io.scos.spi.connector.SourceInstanceId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** B-M-2(ADR-0020 §2): 체크포인트는 점유를 얻은 뒤 읽는다 — 다른 인스턴스가 점유 직전에 전진시킨 위치에서 시작한다. */
@DisplayName("체크포인트는 점유를 얻은 뒤 읽는다(B-M-2, ADR-0020 §2)")
class CollectionRuntimeCheckpointTest {
  static final SourceInstanceId SOURCE = new SourceInstanceId("src-a");
  static final String OTHER_INSTANCE_POSITION = "{\"after\":\"other-instance\"}";

  MutableClock clock = new MutableClock(Instant.parse("2026-10-14T00:00:00Z"));
  MemoryCheckpointPort checkpoints = new MemoryCheckpointPort();
  MemorySourceInstanceStore sources = new MemorySourceInstanceStore();
  AtomicReference<Optional<Checkpoint>> seenByAdapter = new AtomicReference<>();

  /** 점유에 성공하는 순간 다른 인스턴스가 체크포인트를 한 revision 전진시킨다. */
  final class AdvancingClaims extends MemoryWorkClaimPort {
    @Override
    public synchronized Optional<WorkClaim> tryClaim(
        SourceInstanceId source, OwnerId owner, Duration ttl, Instant now) {
      Optional<WorkClaim> claim = super.tryClaim(source, owner, ttl, now);
      if (claim.isPresent())
        checkpoints.advance(source, 0, new Checkpoint(1, OTHER_INSTANCE_POSITION), now);
      return claim;
    }
  }

  /** 받은 체크포인트를 기록하고 한 페이지를 쓰는 어댑터. */
  final SourceAdapter recordingAdapter =
      SourceInstances.adapter(
          input -> seenByAdapter.set(input.checkpoint()),
          sink ->
              sink.accept(
                  CollectionPage.ofItems(
                      List.of(RawItems.item("i1").build()),
                      new Checkpoint(1, "{\"after\":\"mine\"}"))));

  /** 점유 직전에 체크포인트가 전진하는 상황에서 한 번 수집한다. */
  CollectionRuntime.Report collectRacingAnotherInstance() {
    sources.save(SourceInstances.noop(SOURCE, clock.instant()));
    return MemoryCollectionRuntime.over(clock, sources, new AdvancingClaims(), new MemoryRunStore())
        .checkpoints(checkpoints)
        .adapter(recordingAdapter)
        .build()
        .collect(SOURCE, CollectionMode.INCREMENTAL, CancellationToken.none());
  }

  @Nested
  @DisplayName("점유 직전에 다른 인스턴스가 체크포인트를 전진시키면")
  class AdvancedJustBeforeClaim {
    @Test
    @DisplayName("전진한 체크포인트에서 시작한다")
    void startsFromAdvancedCheckpoint() {
      // when
      collectRacingAnotherInstance();

      // then
      assertThat(seenByAdapter.get().map(Checkpoint::valueJson)).contains(OTHER_INSTANCE_POSITION);
    }

    @Test
    @DisplayName("전진한 revision 위에 충돌 없이 커밋한다")
    void commitsOnAdvancedRevision() {
      // when
      CollectionRuntime.Report report = collectRacingAnotherInstance();

      // then
      assertThat(report)
          .extracting(CollectionRuntime.Report::status, CollectionRuntime.Report::pagesApplied)
          .containsExactly(RunStatus.SUCCEEDED, 1);
    }

    @Test
    @DisplayName("체크포인트는 전진한 revision 다음으로 올라간다")
    void advancesPastRacingRevision() {
      // when
      collectRacingAnotherInstance();

      // then
      assertThat(checkpoints.read(SOURCE).revision()).isEqualTo(2);
    }
  }
}
