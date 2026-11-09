package io.scos.collection.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.spi.access.AdminRecords.SourceAccount;
import io.scos.spi.source.SourceIdentityRecord;
import io.scos.spi.source.SourceInstanceId;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("수집 등록과 관리 조회는 같은 원장을 본다(T-c2)")
public abstract class SourceRegistrationAdminContract {
  private SourceRegistrationAdminScenario scenario;

  protected abstract SourceRegistrationAdminScenario newScenario();

  @BeforeEach
  void createScenario() {
    scenario = newScenario();
  }

  @Test
  @DisplayName("수집 등록의 종류를 바꾸면 관리 조회에 즉시 반영된다")
  void sourceKindComesFromCollectionRegistration() {
    // given
    scenario.register("slack");
    // when
    scenario.register("notion");
    // then
    assertThat(scenario.source())
        .contains(new SourceAccount(new SourceInstanceId("slack-a"), "notion"));
  }

  @Test
  @DisplayName("등록 뒤 신원 시드와 병합을 해도 등록 종류를 유지한다")
  void sourceRegistrationSurvivesIdentitySeeding() {
    // given
    scenario.register("slack");
    // when
    scenario.seedAndMerge();
    // then
    assertThat(scenario.source())
        .contains(new SourceAccount(new SourceInstanceId("slack-a"), "slack"));
  }

  @Test
  @DisplayName("수집 병합의 확정 이메일을 관리 조회가 읽는다")
  void collectedIdentityIsVisibleToAdministration() {
    // given
    scenario.register("slack");
    scenario.stageIdentity("alice@example.com");
    // when
    scenario.merge(() -> {});
    // then
    assertThat(scenario.observedIdentities())
        .containsExactly(
            new SourceIdentityRecord(
                "U01",
                Optional.of("alice@example.com"),
                true,
                false,
                false,
                Instant.parse("2026-11-01T00:00:00Z")));
  }

  @Test
  @DisplayName("관리 소스 잠금은 실제 병합의 커밋 뒤 최신 이메일을 읽는다")
  void sourceLockSerializesDirectoryMerge() throws Exception {
    // given
    scenario.register("slack");
    scenario.stageIdentity("new@example.com");
    try (var race = scenario.mergeRace()) {
      var merge = race.holdMerge();
      // when
      var observation = race.readLockedSource();
      // then
      race.requireWaiting(observation);
      race.release();
      assertThat(merge.get()).isEqualTo(AccessMerge.COMPLETE);
      assertThat(observation.get())
          .containsExactly(
              new SourceIdentityRecord(
                  "U01",
                  Optional.of("new@example.com"),
                  true,
                  false,
                  false,
                  Instant.parse("2026-11-01T00:00:00Z")));
    }
  }
}
