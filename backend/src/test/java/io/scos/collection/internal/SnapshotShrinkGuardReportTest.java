package io.scos.collection.internal;

import static io.scos.support.MeterRegistryAssert.assertThatMetrics;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.scos.collection.internal.memory.MemoryOutboxPort;
import io.scos.collection.internal.memory.MemoryRawItemStore;
import io.scos.collection.internal.memory.MemoryRunStore;
import io.scos.collection.internal.memory.MemorySnapshotGuardStore;
import io.scos.collection.internal.memory.MemorySourceChangeLog;
import io.scos.collection.internal.memory.MemorySourceInstanceStore;
import io.scos.collection.internal.memory.MemoryWorkClaimPort;
import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.connector.RawItem;
import io.scos.spi.connector.RawMutation;
import io.scos.spi.connector.SourceInstanceId;
import io.scos.support.LogCapture;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.transaction.support.TransactionOperations;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 스냅샷 축소 보호의 운영 신호(ADR-0028): 정리를 건너뛰면 warn `collection.snapshot.reconcile_guarded`와 지표
 * `scos.collection.snapshot_reconcile_guarded`, 허용 표시로 정리하면 warn
 * `collection.snapshot.shrink_accept_used`, 운영자가 허용하면 감사 이벤트 warn
 * `collection.snapshot.shrink_accepted`. 기준 안의 정리는 아무것도 알리지 않는다.
 */
@DisplayName("스냅샷 축소 보호 운영 신호 — 보호·허용을 이벤트와 지표로 알린다(ADR-0028)")
// 제품 클래스의 공용 로거에 appender를 단다 — 같은 JVM의 다른 테스트가 남긴 줄이 섞이지 않게 혼자 돈다(TESTING §6.3)
@Isolated
class SnapshotShrinkGuardReportTest {
  static final ObjectMapper JSON = new ObjectMapper();
  static final SourceInstanceId SOURCE = new SourceInstanceId("tiro-main");
  static final Instant NOW = Instant.parse("2026-10-16T00:00:00Z");
  static final Duration TTL = Duration.ofSeconds(30);
  static final Instant EIGHT_DAYS_LATER = NOW.plus(Duration.ofDays(8));

  final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  final MemorySourceInstanceStore sources = new MemorySourceInstanceStore();
  final MemorySnapshotGuardStore guards = new MemorySnapshotGuardStore();
  final MemoryWorkClaimPort claims = new MemoryWorkClaimPort();
  final MemoryRunStore runs = new MemoryRunStore();
  final MemoryRawItemStore rawItems = new MemoryRawItemStore();
  final SnapshotShrinkGuard guard =
      new SnapshotShrinkGuard(
          sources, guards, SnapshotShrinkLimits.DEFAULTS, CollectionMetrics.of(registry));
  final RunCompletion completion =
      new RunCompletion(
          TransactionOperations.withoutTransaction(),
          claims,
          runs,
          rawItems,
          new MemoryOutboxPort(),
          new MemorySourceChangeLog(),
          guard);
  final LogCapture log = LogCapture.of(SnapshotShrinkGuard.class);
  final List<String> ids = new ArrayList<>();
  int runCount;

  @BeforeEach
  void givenRegisteredSource() {
    sources.save(SourceInstances.noop(SOURCE, NOW));
  }

  @AfterEach
  void detach() {
    log.close();
  }

  void givenActive(int count) {
    for (int i = 0; i < count; i++) {
      String id = String.format("note-%03d", i);
      rawItems.apply(SOURCE, new RawMutation.Upsert(RawItem.minimal(id, "v1", "{}", NOW)));
      ids.add(id);
    }
  }

  /** 새 run(run-1, run-2, …)이 앞의 observed건만 관측하고 지금(NOW) succeeded + complete_snapshot으로 끝난다. */
  void completeRunObserving(int observed) {
    completeRunObserving(observed, NOW);
  }

  void completeRunObserving(int observed, Instant at) {
    RunId run = new RunId("run-" + ++runCount);
    WorkClaim claim = claims.tryClaim(SOURCE, new OwnerId("worker"), TTL, at).orElseThrow();
    runs.start(run, SOURCE, at);
    runs.observe(run, ids.subList(0, observed));
    completion.complete(
        new RunCompletion.Input(
            claim,
            run,
            RunStatus.SUCCEEDED,
            CollectionCoverage.COMPLETE_SNAPSHOT,
            at,
            0,
            Optional.empty(),
            Optional.empty()));
    claims.release(claim, at);
  }

  static JsonNode json(String text) {
    return JSON.readTree(text);
  }

  @Nested
  @DisplayName("active 20건 소스에서 하나도 관측하지 못하면")
  class Guarded {
    @BeforeEach
    void givenEmptyListing() {
      givenActive(20);

      completeRunObserving(0);
    }

    @Test
    @DisplayName("warn reconcile_guarded를 수와 기준과 함께 남긴다")
    void logsGuardedEvent() {
      // then
      assertThat(log.events("collection.snapshot.reconcile_guarded"))
          .singleElement()
          .isEqualTo(
              json(
                  "{\"level\":\"warn\",\"event\":\"collection.snapshot.reconcile_guarded\","
                      + "\"sourceInstanceId\":\"tiro-main\",\"runId\":\"run-1\",\"detail\":{"
                      + "\"unobservedCount\":20,\"activeCount\":20,\"minActive\":10,\"maxDeletePercent\":50}}"));
    }

    @Test
    @DisplayName("보호 지표를 1 올린다")
    void countsGuardedRun() {
      // then
      assertThatMetrics(registry)
          .hasCounter(CollectionMetrics.SNAPSHOT_RECONCILE_GUARDED, Tags.empty(), 1);
    }
  }

  @Nested
  @DisplayName("active 20건 소스가 세 번 연달아 보호되면")
  class GuardedThreeTimes {
    @BeforeEach
    void givenThreeEmptyListings() {
      givenActive(20);
      completeRunObserving(0);
      completeRunObserving(0);

      completeRunObserving(0);
    }

    @Test
    @DisplayName("error reconcile_guarded_repeatedly를 연속 횟수와 함께 남긴다")
    void logsRepeatedEvent() {
      // then
      assertThat(log.events("collection.snapshot.reconcile_guarded_repeatedly"))
          .singleElement()
          .isEqualTo(
              json(
                  "{\"level\":\"error\",\"event\":\"collection.snapshot.reconcile_guarded_repeatedly\","
                      + "\"sourceInstanceId\":\"tiro-main\",\"runId\":\"run-3\",\"detail\":{"
                      + "\"consecutiveCount\":3,\"unobservedCount\":20,\"activeCount\":20}}"));
    }
  }

  @Nested
  @DisplayName("active 20건 소스가 두 번 연달아 보호되면")
  class GuardedTwice {
    @BeforeEach
    void givenTwoEmptyListings() {
      givenActive(20);
      completeRunObserving(0);

      completeRunObserving(0);
    }

    @Test
    @DisplayName("아직 error로 올리지 않는다")
    void doesNotEscalate() {
      // then
      assertThat(log.events("collection.snapshot.reconcile_guarded_repeatedly")).isEmpty();
    }
  }

  @Nested
  @DisplayName("허용한 지 8일 뒤에 51건을 관측하지 못하면")
  class ExpiredAcceptance {
    @BeforeEach
    void givenStaleAcceptance() {
      givenActive(100);
      guards.acceptShrink(SOURCE, NOW);

      completeRunObserving(49, EIGHT_DAYS_LATER);
    }

    @Test
    @DisplayName("warn shrink_accept_expired를 허용 시각과 함께 남긴다")
    void logsExpiry() {
      // then
      assertThat(log.events("collection.snapshot.shrink_accept_expired"))
          .singleElement()
          .isEqualTo(
              json(
                  "{\"level\":\"warn\",\"event\":\"collection.snapshot.shrink_accept_expired\","
                      + "\"sourceInstanceId\":\"tiro-main\",\"runId\":\"run-1\",\"detail\":{"
                      + "\"acceptedAt\":\"2026-10-16T00:00:00Z\"}}"));
    }
  }

  @Nested
  @DisplayName("축소를 허용한 100건 소스에서 51건을 관측하지 못하면")
  class AcceptanceUsed {
    @BeforeEach
    void givenAcceptedShrink() {
      givenActive(100);
      guards.acceptShrink(SOURCE, NOW);

      completeRunObserving(49);
    }

    @Test
    @DisplayName("warn shrink_accept_used를 지운 수와 함께 남긴다")
    void logsAcceptanceUsed() {
      // then
      assertThat(log.events("collection.snapshot.shrink_accept_used"))
          .singleElement()
          .isEqualTo(
              json(
                  "{\"level\":\"warn\",\"event\":\"collection.snapshot.shrink_accept_used\","
                      + "\"sourceInstanceId\":\"tiro-main\",\"runId\":\"run-1\",\"detail\":{"
                      + "\"deletedCount\":51,\"activeCount\":100}}"));
    }

    @Test
    @DisplayName("보호 지표는 생기지 않는다")
    void doesNotCountGuard() {
      // then
      assertThatMetrics(registry).hasNoMeter(CollectionMetrics.SNAPSHOT_RECONCILE_GUARDED);
    }
  }

  @Nested
  @DisplayName("기준 안에서 정리하면")
  class WithinLimits {
    @BeforeEach
    void givenSmallShrink() {
      givenActive(100);

      completeRunObserving(51);
    }

    @Test
    @DisplayName("축소 보호 이벤트를 남기지 않는다")
    void logsNothing() {
      // then
      assertThat(log.events()).isEmpty();
    }
  }

  @Nested
  @DisplayName("운영자가 축소를 허용하면")
  class OperatorAccepts {
    @Test
    @DisplayName("warn shrink_accepted 감사 이벤트를 남긴다")
    void logsAuditEvent() {
      // when
      guard.accept(SOURCE, NOW);

      // then
      assertThat(log.events("collection.snapshot.shrink_accepted"))
          .singleElement()
          .isEqualTo(
              json(
                  "{\"level\":\"warn\",\"event\":\"collection.snapshot.shrink_accepted\","
                      + "\"sourceInstanceId\":\"tiro-main\"}"));
    }

    @Test
    @DisplayName("허용 표시를 남긴다")
    void recordsAcceptance() {
      // when
      guard.accept(SOURCE, NOW);

      // then
      assertThat(guards.lastAcceptance(SOURCE))
          .contains(
              new SnapshotGuardStore.Acceptance(
                  NOW, Optional.empty(), Optional.empty(), Optional.empty()));
    }

    @Test
    @DisplayName("등록되지 않은 소스는 거부한다")
    void rejectsUnknownSource() {
      // when·then
      assertThatThrownBy(() -> guard.accept(new SourceInstanceId("nope"), NOW))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("Source instance not found: nope");
    }
  }
}
