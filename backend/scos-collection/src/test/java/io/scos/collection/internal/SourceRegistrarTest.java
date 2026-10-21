package io.scos.collection.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.scos.collection.internal.memory.MemorySourceInstanceStore;
import io.scos.engine.support.text.MutableClock;
import io.scos.spi.connector.CallFailureException;
import io.scos.spi.connector.SourceConfig;
import io.scos.spi.connector.SourceInstanceId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 소스 등록(TS SourceRegistrar): 어댑터 설정 검증 뒤 저장한다. credentialRef는 풀지 않지만 스킴 접두사(`<scheme>:`) 모양은 등록 때
 * 본다 — 접두사 없는 ref는 해석 단계에서 거부되므로(ADR-0022 §2) 등록부터 받지 않는다(F4 리뷰 B-M8).
 */
@DisplayName("소스 등록은 설정을 검증해 저장하고, ref는 스킴 모양만 본다(ADR-0022 §2)")
class SourceRegistrarTest {
  static final SourceInstanceId SOURCE = new SourceInstanceId("a");
  static final SourceConfig CONFIG = new SourceConfig(1, "{}");

  static final Duration LATER = Duration.ofHours(1);

  final MemorySourceInstanceStore sources = new MemorySourceInstanceStore();
  final MutableClock clock = new MutableClock(Instant.parse("2026-10-15T00:00:00Z"));
  final SourceRegistrar registrar =
      new SourceRegistrar(
          sources,
          SourceAdapters.of(List.of(SourceInstances.NOOP_ADAPTER, SourceInstances.strictAdapter())),
          clock);

  SourceInstance register(String credentialRef) {
    return registrar.register(
        SOURCE,
        SourceInstances.NOOP_KIND,
        "a",
        CONFIG,
        credentialRef,
        SourceSchedule.DAEMON_DEFAULTS);
  }

  @Test
  @DisplayName("스킴 접두사가 있는 ref는 풀지 않고 그대로 저장한다")
  void storesPrefixedRefWithoutResolving() {
    // when
    register("file:/run/secrets/missing-token");

    // then
    assertThat(sources.get(SOURCE))
        .map(SourceInstance::credentialRef)
        .contains("file:/run/secrets/missing-token");
  }

  @Nested
  @DisplayName("스킴 접두사 모양이 아닌 ref로 등록하면")
  class RefWithoutSchemePrefix {
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"SLACK_TOKEN", "user@example.test:ATATT-secret", "env:", ":x"})
    @DisplayName("거부 메시지에 ref를 싣지 않는다")
    void rejectsWithoutEchoingRef(String credentialRef) {
      // when
      Throwable rejected = catchThrowable(() -> register(credentialRef));

      // then
      assertThat(rejected)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageNotContaining(credentialRef);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"SLACK_TOKEN", "user@example.test:ATATT-secret", "env:", ":x"})
    @DisplayName("IllegalArgumentException으로 거부되고, 등록하지 않는다")
    void rejectsRefWithoutSchemePrefix(String credentialRef) {
      // when
      Throwable rejected = catchThrowable(() -> register(credentialRef));

      // then
      assertThat(rejected).isInstanceOf(IllegalArgumentException.class);
      assertThat(sources.get(SOURCE)).isEmpty();
    }
  }

  @Test
  @DisplayName("어댑터가 설정을 거부하면 ProviderCallException이고, 등록하지 않는다")
  void rejectsConfigTheAdapterRejects() {
    // when
    Throwable rejected =
        catchThrowable(
            () ->
                registrar.register(
                    SOURCE,
                    SourceInstances.STRICT_KIND,
                    "a",
                    new SourceConfig(1, "{\"bad\":true}"),
                    "env:TOKEN",
                    SourceSchedule.DAEMON_DEFAULTS));

    // then
    assertThat(rejected).isInstanceOf(CallFailureException.class);
    assertThat(sources.get(SOURCE)).isEmpty();
  }

  @Test
  @DisplayName("등록한 소스를 저장한 그대로 돌려준다")
  void returnsStoredSource() {
    // when
    SourceInstance registered = register("env:TOKEN");

    // then
    assertThat(sources.get(SOURCE)).contains(registered);
  }

  @Nested
  @DisplayName("등록한 소스를 끄면")
  class Disabled {
    SourceInstance registered;
    SourceInstance disabled;

    @BeforeEach
    void registerThenDisable() {
      registered = register("env:TOKEN");
      clock.advance(LATER);
      disabled = registrar.setEnabled(SOURCE, false);
    }

    @Test
    @DisplayName("끈 소스를 저장한 그대로 돌려준다")
    void returnsStoredSource() {
      // then
      assertThat(sources.get(SOURCE)).contains(disabled);
    }

    @Test
    @DisplayName("데몬이 감독하는 enabled 목록에서 빠진다")
    void leavesEnabledList() {
      // then
      assertThat(sources.listEnabled()).isEmpty();
    }

    @Test
    @DisplayName("enabled와 수정 시각만 바뀐다")
    void changesOnlyEnabledAndUpdatedAt() {
      // then
      assertThat(sources.get(SOURCE))
          .contains(
              new SourceInstance(
                  registered.id(),
                  registered.kind(),
                  registered.displayName(),
                  registered.config(),
                  registered.credentialRef(),
                  false,
                  registered.schedule(),
                  registered.createdAt(),
                  clock.instant()));
    }

    @Test
    @DisplayName("다시 켜면 enabled 목록에 돌아온다")
    void reenableReturnsToEnabledList() {
      // when
      registrar.setEnabled(SOURCE, true);

      // then
      assertThat(sources.listEnabled()).extracting(SourceInstance::id).containsExactly(SOURCE);
    }
  }

  @Test
  @DisplayName("등록되지 않은 소스의 켜기·끄기는 거부한다")
  void unknownSourceCannotBeToggled() {
    // when
    Throwable rejected = catchThrowable(() -> registrar.setEnabled(SOURCE, false));

    // then
    assertThat(rejected)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Source instance not found: a");
  }
}
