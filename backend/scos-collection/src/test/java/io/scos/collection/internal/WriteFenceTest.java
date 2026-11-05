package io.scos.collection.internal;

import static io.scos.collection.internal.AclCommitScenario.*;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.source.SourceInstanceId;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("쓰기 가드는 점유·run·출처·설정을 같은 계약으로 검증한다")
class WriteFenceTest {
  private AclCommitScenario scenario;

  @BeforeEach
  void setUp() {
    scenario = AclCommitScenario.memory();
  }

  @Test
  @DisplayName("만료 점유는 쓰기를 거부한다")
  void expiredClaimIsRejected() {
    scenario.clock.advance(TTL);
    // when·then
    assertThatThrownBy(this::requireWritable).isInstanceOf(ClaimLostException.class);
  }

  @Test
  @DisplayName("끝난 run은 쓰기를 거부한다")
  void finishedRunIsRejected() {
    scenario
        .ports
        .runs()
        .finish(
            RUN,
            RunOutcome.succeeded(0),
            CollectionCoverage.INCREMENTAL,
            T2,
            0,
            AccessResult.SKIPPED,
            Optional.empty());
    // when·then
    assertThatThrownBy(this::requireWritable).isInstanceOf(RunStateException.class);
  }

  @Test
  @DisplayName("변경한 설정은 쓰기를 거부한다")
  void changedConfigurationIsRejected() {
    scenario
        .ports
        .sources()
        .save(SourceInstances.of(SOURCE, "noop", "{\"changed\":true}", "env:NOOP_TOKEN", T0));
    // when·then
    assertThatThrownBy(this::requireWritable)
        .isInstanceOf(SourceConfigurationChangedException.class);
  }

  @Test
  @DisplayName("다른 출처 descriptor는 쓰기를 거부한다")
  void differentDescriptorSourceIsRejected() {
    var other = SourceInstances.noop(new SourceInstanceId("other"), T0).descriptor();
    // when·then
    assertThatThrownBy(
            () ->
                new WriteFence(scenario.ports.stores())
                    .requireWritable(scenario.claim, RUN, Optional.of(other), T2))
        .isInstanceOf(SourceConfigurationChangedException.class);
  }

  @Test
  @DisplayName("설정 저장소가 없으면 쓰기를 거부한다")
  void missingConfigurationStoreIsRejected() {
    var p = scenario.ports;
    var stores =
        new CollectionStores(
            p.tx(),
            p.claims(),
            p.runs(),
            p.rawItems(),
            p.checkpoints(),
            p.outbox(),
            p.changes(),
            p.rawAcls(),
            p.accessObservations());
    // when·then
    assertThatThrownBy(
            () ->
                new WriteFence(stores)
                    .requireWritable(scenario.claim, RUN, Optional.of(scenario.source), T2))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Source configuration store is not configured");
  }

  private void requireWritable() {
    new WriteFence(scenario.ports.stores())
        .requireWritable(
            scenario.claim, RUN, Optional.of(scenario.source), scenario.clock.instant());
  }
}
