package io.scos.collection.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.spi.connector.SourceConfig;
import io.scos.spi.connector.SourceInstanceId;
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
