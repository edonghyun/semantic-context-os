package io.scos.collection.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.spi.connector.SourceConfig;
import io.scos.spi.source.SourceInstanceId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** 소스 등록 저장 포트 계약. */
@DisplayName("소스 등록 저장소는 id별 등록 하나를 둔다")
public abstract class SourceInstanceStoreContract {
  protected static final Instant T0 = Instant.parse("2026-10-14T00:00:00Z");
  protected SourceInstanceStore store;

  static final SourceInstanceId SCHEDULED = new SourceInstanceId("notion-main");
  static final SourceSchedule SIX_HOURS_DAILY_SNAPSHOT =
      new SourceSchedule(Optional.of(Duration.ofHours(6)), Optional.of(Duration.ofDays(1)));

  protected abstract SourceInstanceStore newStore();

  @BeforeEach
  void setUpContract() {
    store = newStore();
  }

  static SourceInstance instance(String id, boolean enabled) {
    return new SourceInstance(
        new SourceInstanceId(id),
        "slack",
        "name " + id,
        new SourceConfig(1, "{\"conversationTypes\":[\"public_channel\"]}"),
        "SLACK_TOKEN_" + id,
        enabled,
        SourceSchedule.DAEMON_DEFAULTS,
        SnapshotShrinkOverrides.DAEMON_DEFAULTS,
        T0,
        T0);
  }

  /** enabled b·a, disabled c. */
  void givenThreeSources() {
    store.save(instance("b", true));
    store.save(instance("a", true));
    store.save(instance("c", false));
  }

  static List<String> ids(List<SourceInstance> sources) {
    return sources.stream().map(s -> s.id().value()).toList();
  }

  /** a를 이름을 바꾸고 비활성으로 다시 저장한 값. */
  static final SourceInstance RENAMED_A =
      new SourceInstance(
          new SourceInstanceId("a"),
          "slack",
          "renamed",
          instance("a", true).config(),
          "SLACK_TOKEN_a",
          false,
          SourceSchedule.DAEMON_DEFAULTS,
          SnapshotShrinkOverrides.DAEMON_DEFAULTS,
          T0,
          T0.plusSeconds(1));

  @Nested
  @DisplayName("enabled b·a와 disabled c를 저장한 상태에서")
  class ThreeSources {
    @BeforeEach
    void givenSaved() {
      givenThreeSources();
    }

    @Test
    @DisplayName("없는 소스의 소유 충돌 보고는 소스를 만들지 않는다")
    void ownershipSkippedDoesNotCreateMissingSource() {
      var source = new SourceInstanceId("missing");
      store.recordOwnershipSkipped(source, 2);
      // then
      assertThat(store.ownershipSkipped(source)).isZero();
      assertThat(store.get(source)).isEmpty();
    }

    @Test
    @DisplayName("소유 충돌 수는 소스별 최근 투영 값으로 대체된다")
    void ownershipSkippedTracksLatestProjection() {
      store.save(instance("a", true));
      store.save(instance("b", true));
      store.recordOwnershipSkipped(new SourceInstanceId("a"), 2);
      store.recordOwnershipSkipped(new SourceInstanceId("a"), 1);
      // then
      assertThat(store.ownershipSkipped(new SourceInstanceId("a"))).isEqualTo(1);
      assertThat(store.ownershipSkipped(new SourceInstanceId("b"))).isZero();
    }

    @Test
    @DisplayName("전체 목록은 비활성 소스도 id 순서로 포함한다")
    void allSourcesIncludeDisabled() {
      // then
      assertThat(ids(store.listAll())).containsExactly("a", "b", "c");
    }

    @Test
    @DisplayName("현재 수집 설정이 바뀌면 이전 설정의 쓰기를 거부한다")
    void changedCollectionConfigurationIsRejected() {
      // given
      var expected = instance("a", true).descriptor();
      saveChangedConfiguration();
      // when·then
      assertThatThrownBy(() -> store.requireCollectionConfiguration(expected))
          .isInstanceOf(SourceConfigurationChangedException.class);
    }

    @Test
    @DisplayName("표시 이름과 enabled 변경은 수집 의미 설정을 바꾸지 않는다")
    void displayChangesDoNotInvalidateCollectionConfiguration() {
      // given
      var expected = instance("a", true).descriptor();
      store.save(RENAMED_A);
      // when·then
      assertThatCode(() -> store.requireCollectionConfiguration(expected))
          .doesNotThrowAnyException();
    }

    private void saveChangedConfiguration() {
      var original = instance("a", true);
      store.save(
          new SourceInstance(
              original.id(),
              original.kind(),
              original.displayName(),
              new SourceConfig(1, "{\"conversationTypes\":[\"private_channel\"]}"),
              original.credentialRef(),
              original.enabled(),
              original.schedule(),
              original.shrinkGuard(),
              original.createdAt(),
              T0.plusSeconds(1)));
    }

    @Test
    @DisplayName("get은 저장한 값을 그대로 돌려준다")
    void getReturnsSaved() {
      // when
      Optional<SourceInstance> got = store.get(new SourceInstanceId("a"));

      // then
      assertThat(got).contains(instance("a", true));
    }

    @Test
    @DisplayName("저장된 적 없는 id의 get은 비어 있다")
    void getUnknownIsEmpty() {
      // when
      Optional<SourceInstance> got = store.get(new SourceInstanceId("zz"));

      // then
      assertThat(got).isEmpty();
    }

    @Test
    @DisplayName("listEnabled는 enabled만 id 순으로 돌려준다")
    void listEnabledInIdOrder() {
      // when
      List<SourceInstance> enabled = store.listEnabled();

      // then
      assertThat(ids(enabled)).containsExactly("a", "b");
    }

    @Test
    @DisplayName("같은 id를 다시 저장하면 바뀐 값이 보인다")
    void saveIsUpsert() {
      // when
      store.save(RENAMED_A);

      // then
      assertThat(store.get(new SourceInstanceId("a"))).contains(RENAMED_A);
    }

    @Test
    @DisplayName("같은 id를 다시 저장하면 enabled 목록에도 반영된다")
    void saveUpdatesEnabledList() {
      // when
      store.save(RENAMED_A);

      // then
      assertThat(ids(store.listEnabled())).containsExactly("b");
    }
  }

  @Test
  @DisplayName("소스별 주기를 저장한 그대로 돌려준다")
  void scheduleRoundTrips() {
    // given
    SourceInstance scheduled = SourceInstances.scheduled(SCHEDULED, SIX_HOURS_DAILY_SNAPSHOT, T0);

    // when
    store.save(scheduled);

    // then
    assertThat(store.get(SCHEDULED)).contains(scheduled);
  }
}
