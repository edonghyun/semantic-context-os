package io.scos.collection.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.spi.connector.ReconciliationContext.Path;
import io.scos.spi.connector.ReconciliationKind;
import io.scos.spi.connector.SourceOrder;
import io.scos.spi.source.SourceInstanceId;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("본문 완료 저장소는 검증된 소속과 버전을 보존한다")
public abstract class BodyCompletionStoreContract {
  protected static final SourceInstanceId SOURCE = new SourceInstanceId("body-source");
  static final SourceInstanceId OTHER_SOURCE = new SourceInstanceId("other-body-source");
  static final RunId OTHER_RUN = new RunId("other-discovery-run");
  static final String GENERATION = "roots-generation";
  static final String PAGE = "page";
  static final String PREVIOUS_BLOCK = "old-block";
  static final String CURRENT_BLOCK = "new-block";
  static final Instant COMPLETED_AT = Instant.parse("2026-10-25T00:00:00Z");
  static final long CLOCK_NANOS = 123_456_789L;
  static final SourceOrder ORDER = new SourceOrder("notion.page.v1", "0001");
  protected BodyCompletionStore store;

  protected abstract BodyCompletionStore newStore();

  @BeforeEach
  void prepare() {
    store = newStore();
  }

  static final RunId DISCOVERY_RUN = new RunId("discovery-run");

  @Test
  @DisplayName("완료 재저장은 현재 블록 소속으로 교체한다")
  void replacesCompletedMembership() {
    previousMembership();
    store.save(SOURCE, completedBody(Set.of(CURRENT_BLOCK)));
    // then
    assertThat(store.members(SOURCE, GENERATION, PAGE)).containsExactly(CURRENT_BLOCK);
  }

  @Test
  @DisplayName("완료 재저장은 사라진 블록의 역방향 owner도 제거한다")
  void removesReplacedOwner() {
    previousMembership();
    store.save(SOURCE, completedBody(Set.of(CURRENT_BLOCK)));
    // then
    assertThat(store.owners(SOURCE, GENERATION, PREVIOUS_BLOCK)).isEmpty();
  }

  @Test
  @DisplayName("발견 원장은 다른 소스에 노출되지 않는다")
  void discoveriesAreSourceScoped() {
    store.recordDiscovery(SOURCE, DISCOVERY_RUN, discovery(true));
    var found = store.discoveries(OTHER_SOURCE, DISCOVERY_RUN);
    // then
    assertThat(found).isEmpty();
  }

  @Test
  @DisplayName("발견 원장은 다른 run에 노출되지 않는다")
  void discoveriesAreRunScoped() {
    store.recordDiscovery(SOURCE, DISCOVERY_RUN, discovery(true));
    var found = store.discoveries(SOURCE, OTHER_RUN);
    // then
    assertThat(found).isEmpty();
  }

  @Test
  @DisplayName("미완료 비교는 다른 소스에 노출되지 않는다")
  void comparisonsAreSourceScoped() {
    store.beginComparison(SOURCE, DISCOVERY_RUN, comparison("before", "v0", COMPLETED_AT));
    var found = store.comparison(OTHER_SOURCE, DISCOVERY_RUN, GENERATION, PAGE);
    // then
    assertThat(found).isEmpty();
  }

  @Test
  @DisplayName("미완료 비교는 다른 run에 노출되지 않는다")
  void comparisonsAreRunScoped() {
    store.beginComparison(SOURCE, DISCOVERY_RUN, comparison("before", "v0", COMPLETED_AT));
    var found = store.comparison(SOURCE, OTHER_RUN, GENERATION, PAGE);
    // then
    assertThat(found).isEmpty();
  }

  private void previousMembership() {
    store.save(SOURCE, completedBody(Set.of(PREVIOUS_BLOCK)));
  }

  private BodyCompletionStore.Body completedBody(Set<String> blocks) {
    return new BodyCompletionStore.Body(GENERATION, PAGE, "v1", ORDER, COMPLETED_AT, blocks);
  }

  @Test
  @DisplayName("본문을 확인한 경로와 시각을 완료 증거와 함께 보존한다")
  void persistsBodyReadingHistory() {
    // given
    var body =
        new BodyCompletionStore.Body(
                "roots-generation",
                "page",
                "v1",
                ORDER,
                COMPLETED_AT,
                Set.of("block"),
                Optional.of(DISCOVERY_RUN))
            .readAt(Path.INCREMENTAL, COMPLETED_AT.plusNanos(CLOCK_NANOS));
    // when
    store.save(SOURCE, body);
    // then
    assertThat(store.find(SOURCE, "roots-generation", "page")).contains(body);
  }

  @Test
  @DisplayName("발견 상태를 저장하면 그 상태의 미완료 비교 기준을 닫는다")
  void discoveryClosesComparison() {
    // given
    store.beginComparison(SOURCE, DISCOVERY_RUN, comparison("before-full", "v0", COMPLETED_AT));
    // when
    store.recordDiscovery(SOURCE, DISCOVERY_RUN, discovery(true));
    // then
    assertThat(store.comparison(SOURCE, DISCOVERY_RUN, "roots-generation", "page")).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(longs = {0, CLOCK_NANOS})
  @DisplayName("중단한 전체 비교의 최초 상태와 탐색 시각을 원래 정밀도로 보존한다")
  void retryKeepsStateBeforeFullRead(long nanos) {
    // given
    var first = comparison("before-full", "v0", COMPLETED_AT.plusNanos(nanos));
    store.beginComparison(SOURCE, DISCOVERY_RUN, first);
    // when
    store.beginComparison(SOURCE, DISCOVERY_RUN, comparison("already-partial", "v1", COMPLETED_AT));
    // then
    assertThat(store.comparison(SOURCE, DISCOVERY_RUN, "roots-generation", "page")).contains(first);
  }

  @Test
  @DisplayName("같은 run의 같은 상태 재시도는 첫 발견과 분모를 중복하거나 덮지 않는다")
  void retryPreservesFirstDiscovery() {
    // given
    var first = discovery(true);
    store.recordDiscovery(SOURCE, DISCOVERY_RUN, first);
    // when
    store.recordDiscovery(SOURCE, DISCOVERY_RUN, discovery(false));
    // then
    assertThat(store.discoveries(SOURCE, DISCOVERY_RUN)).containsExactly(first);
  }

  @Test
  @DisplayName("전체 비교의 발견 상태와 eligible 분모를 같은 기록으로 다시 읽는다")
  void reloadsReconciliationDiscovery() {
    // given
    var discovery = discovery(true);
    // when
    store.recordDiscovery(SOURCE, DISCOVERY_RUN, discovery);
    // then
    assertThat(store.discoveries(SOURCE, DISCOVERY_RUN)).containsExactly(discovery);
  }

  private ReconciliationDiscovery.Comparison comparison(String state, String version, Instant at) {
    return new ReconciliationDiscovery.Comparison(
        "roots-generation",
        "page",
        Optional.of(state),
        Optional.of(version),
        Optional.of(at),
        false);
  }

  private ReconciliationDiscovery.Discovery discovery(boolean eligible) {
    return new ReconciliationDiscovery.Discovery(
        "roots-generation",
        "page",
        "state-v1",
        ReconciliationKind.PAGE,
        eligible
            ? Optional.of(ReconciliationDiscovery.Classification.INCREMENTAL_MISS)
            : Optional.empty(),
        eligible);
  }

  @Test
  @DisplayName("완료 버전과 해당 페이지의 블록 소속을 다시 읽는다")
  void reloadsCompletion() {
    // given
    var body =
        new BodyCompletionStore.Body(
            "roots-generation", "page", "v1", ORDER, COMPLETED_AT, Set.of("block"));
    // when
    store.save(SOURCE, body);
    // then
    assertThat(store.find(SOURCE, "roots-generation", "page")).contains(body);
  }

  @Test
  @DisplayName("미완료 본문의 관측을 run별로 다시 읽는다")
  void reloadsPendingEnumeration() {
    // given
    var pending =
        new BodyCompletionStore.Pending(
            "roots-generation", "page", "v1", ORDER, new RunId("run-body"), Set.of("block"));
    // when
    store.savePending(SOURCE, pending);
    // then
    assertThat(store.pending(SOURCE, "roots-generation", "page")).contains(pending);
  }

  @Test
  @DisplayName("완료를 저장하면 그 페이지의 미완료 관측을 닫는다")
  void completionClosesEnumeration() {
    // given
    store.savePending(
        SOURCE,
        new BodyCompletionStore.Pending(
            "roots-generation", "page", "v1", ORDER, new RunId("run-body"), Set.of("block")));
    var body =
        new BodyCompletionStore.Body(
            "roots-generation", "page", "v1", ORDER, COMPLETED_AT, Set.of("block"));
    // when
    store.save(SOURCE, body);
    // then
    assertThat(store.pending(SOURCE, "roots-generation", "page")).isEmpty();
  }

  @Test
  @DisplayName("같은 블록의 검증된 두 소유자를 모두 읽는다")
  void readsAllOwners() {
    // given
    store.save(
        SOURCE,
        new BodyCompletionStore.Body(
            "roots-generation", "page", "v1", ORDER, COMPLETED_AT, Set.of("block")));
    store.save(
        SOURCE,
        new BodyCompletionStore.Body(
            "roots-generation", "child", "v1", ORDER, COMPLETED_AT, Set.of("block")));
    // when
    var owners = store.owners(SOURCE, "roots-generation", "block");
    // then
    assertThat(owners).containsExactlyInAnyOrder("page", "child");
  }

  @Test
  @DisplayName("미완료 버전 교체도 이전 관측 블록의 소속은 잃지 않는다")
  void retainsOwnershipAcrossIncompleteVersions() {
    // given
    store.savePending(
        SOURCE,
        new BodyCompletionStore.Pending(
            "roots-generation", "page", "v1", ORDER, new RunId("run-body"), Set.of("old-block")));
    // when
    store.savePending(
        SOURCE,
        new BodyCompletionStore.Pending(
            "roots-generation", "page", "v2", ORDER, new RunId("run-body"), Set.of("new-block")));
    // then
    assertThat(store.members(SOURCE, "roots-generation", "page"))
        .containsExactlyInAnyOrder("old-block", "new-block");
    assertThat(store.owners(SOURCE, "roots-generation", "old-block")).containsExactly("page");
  }

  @Test
  @DisplayName("안정된 본문 열거를 완료한 run도 함께 보존한다")
  void reloadsCompletingRun() {
    // given
    var run = new RunId("completed-body-run");
    var body =
        new BodyCompletionStore.Body(
            "roots-generation",
            "page",
            "v1",
            ORDER,
            COMPLETED_AT,
            Set.of("block"),
            Optional.of(run));
    // when
    store.save(SOURCE, body);
    // then
    assertThat(store.find(SOURCE, "roots-generation", "page").orElseThrow().completedRun())
        .contains(run);
  }

  @Test
  @DisplayName("현재 소스·범위·run의 미완료 본문 존재 여부를 읽는다")
  void findsPendingForCurrentRun() {
    var run = new RunId("pending-run");
    store.savePending(
        SOURCE,
        new BodyCompletionStore.Pending("roots-generation", "page", "v1", ORDER, run, Set.of()));
    boolean pending = store.hasPending(SOURCE, "roots-generation", run);
    // then
    assertThat(pending).isTrue();
  }

  @ParameterizedTest
  @CsvSource({
    "another-source, roots-generation, pending-run",
    "body-source, another-generation, pending-run",
    "body-source, roots-generation, previous-run"
  })
  @DisplayName("다른 소스·범위·run의 미완료 본문은 현재 run 존재 조회에 섞이지 않는다")
  void ignoresPendingOutsideCurrentRun(String source, String fingerprint, String run) {
    store.savePending(
        new SourceInstanceId(source),
        new BodyCompletionStore.Pending(
            fingerprint, "page", "v1", ORDER, new RunId(run), Set.of()));
    boolean pending = store.hasPending(SOURCE, "roots-generation", new RunId("pending-run"));
    // then
    assertThat(pending).isFalse();
  }
}
