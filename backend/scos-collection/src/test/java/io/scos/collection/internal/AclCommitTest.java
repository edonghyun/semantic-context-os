package io.scos.collection.internal;

import static io.scos.collection.internal.AclCommitScenario.GRANTED;
import static io.scos.collection.internal.AclCommitScenario.OBSERVATION_STEP;
import static io.scos.collection.internal.AclCommitScenario.RUN;
import static io.scos.collection.internal.AclCommitScenario.SOURCE;
import static io.scos.collection.internal.AclCommitScenario.T2;
import static org.assertj.core.api.Assertions.assertThat;

import io.scos.collection.internal.memory.MemoryAccessObservationStore;
import io.scos.spi.access.DocumentAcl.Status;
import io.scos.spi.connector.AclPage;
import io.scos.spi.connector.RawAcl;
import io.scos.spi.source.GroupSnapshot;
import io.scos.spi.source.SourceIdentityRecord;
import io.scos.support.LogCapture;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("메모리 AclCommitTest 공통 계약")
class AclCommitTest extends AclCommitContract {
  @Override
  protected AclCommitScenario newScenario() {
    return AclCommitScenario.memory();
  }

  @Test
  @DisplayName("미래 그룹 관측 뒤의 탈퇴가 병합에 반영된다")
  void futureGroupCannotFenceRestriction() {
    page(
        new AclPage(
            Map.of(),
            List.of(new GroupSnapshot("C1", Set.of("U1"), T2.plusSeconds(3600))),
            List.of(new SourceIdentityRecord("U1", Optional.empty(), false, false, false, T2))));
    scenario.mergeAccess();
    scenario.ports.accessObservations().discard(RUN);
    scenario.clock.advance(OBSERVATION_STEP);
    page(
        new AclPage(
            Map.of(), List.of(new GroupSnapshot("C1", Set.of(), T2.plusSeconds(1))), List.of()));
    scenario.mergeAccess();
    // then
    assertThat(
            ((MemoryAccessObservationStore) scenario.ports.accessObservations())
                .snapshot(SOURCE)
                .groups()
                .get("C1"))
        .isEqualTo(new GroupSnapshot("C1", Set.of(), T2.plusSeconds(1)));
  }

  @Test
  @DisplayName("미래 신원 관측 뒤의 삭제가 병합에 반영된다")
  void futureIdentityCannotFenceDeletion() {
    page(
        new AclPage(
            Map.of(),
            List.of(),
            List.of(
                new SourceIdentityRecord(
                    "U1", Optional.empty(), false, false, false, T2.plusSeconds(3600)))));
    scenario.mergeAccess();
    scenario.ports.accessObservations().discard(RUN);
    scenario.clock.advance(OBSERVATION_STEP);
    page(
        new AclPage(
            Map.of(),
            List.of(),
            List.of(
                new SourceIdentityRecord(
                    "U1", Optional.empty(), false, false, true, T2.plusSeconds(1)))));
    scenario.mergeAccess();
    // then
    assertThat(
            ((MemoryAccessObservationStore) scenario.ports.accessObservations())
                .snapshot(SOURCE)
                .identities()
                .get("U1"))
        .isEqualTo(
            new SourceIdentityRecord(
                "U1", Optional.empty(), false, false, true, T2.plusSeconds(1)));
  }

  @Test
  @DisplayName("미래 시각 보정은 원천·종류·원래와 보정 시각을 경고로 남긴다")
  void futureObservationLogsWarning() {
    try (var log = LogCapture.of(AclCommit.class)) {
      scenario.access(RawAcl.observed(Status.OK, GRANTED.keys(), T2.plusSeconds(3600)));
      // then
      assertThat(log.events("collection.access.future_observation"))
          .singleElement()
          .satisfies(
              event -> {
                assertThat(
                        List.of(
                            event.path("level").asString(),
                            event.path("sourceInstanceId").asString(),
                            event.path("detail").path("kind").asString(),
                            event.path("detail").path("observedAt").asString(),
                            event.path("detail").path("cappedAt").asString()))
                    .containsExactly(
                        "warn",
                        "acl-source",
                        "acl",
                        "2026-10-14T01:00:02Z",
                        "2026-10-14T00:00:02Z");
              });
    }
  }

  private void page(AclPage page) {
    scenario.accesses(true).commit(scenario.claim, RUN, scenario.source, page);
  }
}
