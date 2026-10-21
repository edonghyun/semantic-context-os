package io.scos;

import static io.scos.CommandScenario.PUBLIC_CHANNELS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.collection.internal.SnapshotShrinkOverrides;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 스냅샷 축소 보호의 운영 명령(ADR-0028, 제품 조립): `source add
 * --snapshot-guard-min-active·--snapshot-guard-max-delete-percent`가 소스별 기준을 등록하고, `source
 * accept-shrink <id>`가 다음 정리 한 번의 축소를 허용한다.
 */
@Tag("db")
@DisplayName("스냅샷 축소 보호 명령 — 소스별 기준 등록과 축소 허용(ADR-0028)")
class CommandsSnapshotShrinkTest {
  static final String SOURCE = "slack-synthetic";
  static final int SUCCESS = 0;

  CommandScenario commands;

  @BeforeEach
  void assemble() {
    commands = new CommandScenario();
  }

  @AfterEach
  void close() {
    commands.close();
  }

  @Nested
  @DisplayName("source add에 축소 보호 기준을 주면")
  class AddWithLimits {
    @Test
    @DisplayName("등록된 소스가 그 기준을 가진다")
    void storesLimits() {
      // when
      commands.addSource(
          SOURCE,
          PUBLIC_CHANNELS,
          "--snapshot-guard-min-active=200",
          "--snapshot-guard-max-delete-percent=80");

      // then
      assertThat(commands.shrinkGuard(SOURCE))
          .isEqualTo(new SnapshotShrinkOverrides(Optional.of(200), Optional.of(80)));
    }

    @Test
    @DisplayName("주지 않은 기준은 데몬 기본값을 따른다")
    void defaultsToDaemon() {
      // when
      commands.addSource(SOURCE, PUBLIC_CHANNELS);

      // then
      assertThat(commands.shrinkGuard(SOURCE)).isEqualTo(SnapshotShrinkOverrides.DAEMON_DEFAULTS);
    }

    @ParameterizedTest(name = "{0}={1}")
    @CsvSource({
      "--snapshot-guard-min-active, 0",
      "--snapshot-guard-min-active, abc",
      "--snapshot-guard-max-delete-percent, 0",
      "--snapshot-guard-max-delete-percent, 101"
    })
    @DisplayName("범위를 벗어난 값은 옵션과 입력을 밝혀 실패한다")
    void rejectsOutOfRange(String option, String raw) {
      // when·then
      assertThatThrownBy(() -> commands.addSource(SOURCE, PUBLIC_CHANNELS, option + "=" + raw))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageStartingWith(option + " must be ");
    }
  }

  @Nested
  @DisplayName("소스별 기준을 둔 소스를 기준 옵션 없이 다시 등록하면")
  class ReAddWithoutLimits {
    @BeforeEach
    void givenSourceWithLimits() {
      if (commands.addSource(
              SOURCE,
              PUBLIC_CHANNELS,
              "--snapshot-guard-min-active=200",
              "--snapshot-guard-max-delete-percent=80")
          != SUCCESS) throw new IllegalStateException("given 준비 실패: 기준과 함께 등록하지 못했다");
    }

    @Test
    @DisplayName("이미 등록한 기준을 유지한다")
    void keepsLimits() {
      // when
      commands.addSource(SOURCE, PUBLIC_CHANNELS, "--interval-seconds=3600");

      // then
      assertThat(commands.shrinkGuard(SOURCE))
          .isEqualTo(new SnapshotShrinkOverrides(Optional.of(200), Optional.of(80)));
    }

    @Test
    @DisplayName("준 옵션만 새 값으로 바뀐다")
    void replacesOnlyGivenLimit() {
      // when
      commands.addSource(SOURCE, PUBLIC_CHANNELS, "--snapshot-guard-max-delete-percent=60");

      // then
      assertThat(commands.shrinkGuard(SOURCE))
          .isEqualTo(new SnapshotShrinkOverrides(Optional.of(200), Optional.of(60)));
    }
  }

  @Nested
  @DisplayName("complete_snapshot이 없는 소스에 source accept-shrink를 실행하면")
  class AcceptIncrementalOnly {
    @Test
    @DisplayName("적용되지 않는 소스라고 실패한다")
    void rejectsIncrementalSource() {
      // given
      commands.givenIncrementalOnlySource("jira-main");

      // when·then
      assertThatThrownBy(() -> commands.run("source", "accept-shrink", "jira-main"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage(
              "accept-shrink applies only to sources that collect complete snapshots: jira-main (jira)");
    }
  }

  @Nested
  @DisplayName("등록된 소스에 source accept-shrink를 실행하면")
  class AcceptShrink {
    int exit;

    @BeforeEach
    void givenRegisteredSourceAccepted() {
      if (commands.addSource(SOURCE, PUBLIC_CHANNELS) != SUCCESS)
        throw new IllegalStateException("given 준비 실패: 소스를 등록하지 못했다");

      exit = commands.run("source", "accept-shrink", SOURCE);
    }

    @Test
    @DisplayName("종료 코드는 0이다")
    void succeeds() {
      // then
      assertThat(exit).isEqualTo(SUCCESS);
    }

    @Test
    @DisplayName("소모되지 않은 허용 표시가 남는다")
    void recordsPendingAcceptance() {
      // then
      assertThat(commands.shrinkAcceptance(SOURCE))
          .hasValueSatisfying(a -> assertThat(a.pending()).isTrue());
    }
  }

  @Nested
  @DisplayName("등록되지 않은 소스에 source accept-shrink를 실행하면")
  class AcceptUnknown {
    @Test
    @DisplayName("소스가 없다고 실패한다")
    void rejectsUnknownSource() {
      // when·then
      assertThatThrownBy(() -> commands.run("source", "accept-shrink", "nope"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("Source instance not found: nope");
    }
  }
}
