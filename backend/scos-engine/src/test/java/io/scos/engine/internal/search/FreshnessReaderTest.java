package io.scos.engine.internal.search;

import static io.scos.engine.internal.search.FreshnessReaderScenario.BOTH_SOURCE_KEYS;
import static io.scos.engine.internal.search.FreshnessReaderScenario.DRIVE;
import static io.scos.engine.internal.search.FreshnessReaderScenario.NOW;
import static io.scos.engine.internal.search.FreshnessReaderScenario.OLDEST;
import static io.scos.engine.internal.search.FreshnessReaderScenario.RECENT;
import static io.scos.engine.internal.search.FreshnessReaderScenario.SLACK;
import static io.scos.engine.internal.search.FreshnessReaderScenario.SPACE;
import static io.scos.spi.store.ContextUnits.unit;
import static org.assertj.core.api.Assertions.assertThat;

import io.scos.engine.Freshness;
import io.scos.spi.store.AccessScope;
import io.scos.store.memory.MemoryUnitMaintenance;
import io.scos.store.memory.MemoryUnitStore;
import io.scos.store.memory.MemoryVectorIndex;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** CONTRACTS B9: 무제한은 최신 capturedAt, 제한 범위는 원천별 마지막 성공 수집 시각의 min을 쓴다. */
@DisplayName("최신성은 접근 범위의 기준 시각부터 지금까지로 판정한다(B9)")
class FreshnessReaderTest {
  // B9의 stale 경계에서 min 집계를 함께 관찰한다.
  static final double STALE_BOUNDARY_HOURS = 12.0;

  MemoryUnitMaintenance units =
      new MemoryUnitMaintenance(new MemoryUnitStore(), new MemoryVectorIndex());
  FreshnessReader reader = new FreshnessReader(units, Clock.fixed(NOW, ZoneOffset.UTC));

  @ParameterizedTest(name = "latest {0} → staleHours {1}")
  @CsvSource({
    "2026-10-13T11:00:00.000Z, 1.0",
    "2026-10-13T00:00:01.000Z, 11.999722222222223",
    "2026-10-13T00:00:00.000Z, 12.0",
    "2026-10-12T00:00:00.000Z, 36.0",
    "2026-10-13T13:00:00.000Z, 0.0"
  })
  @DisplayName("staleHours는 최신 capturedAt부터 지금까지의 시간이다(미래 시각은 0)")
  void staleHours(String latest, double hours) {
    // given
    units.upsert(SPACE, List.of(unit("u").capturedAt(latest).build()));

    // when
    Freshness f = reader.read(AccessScope.singleUser(), SPACE);

    // then
    assertThat(f.latestCapturedAt()).contains(latest);
    assertThat(f.staleHours()).contains(hours);
  }

  @ParameterizedTest(name = "latest {0} → stale {1}")
  @CsvSource({
    "2026-10-13T11:00:00.000Z, false",
    "2026-10-13T00:00:01.000Z, false",
    "2026-10-13T00:00:00.000Z, true",
    "2026-10-12T00:00:00.000Z, true",
    "2026-10-13T13:00:00.000Z, false"
  })
  @DisplayName("stale는 정확히 12시간부터다")
  void staleBoundary(String latest, boolean stale) {
    // given
    units.upsert(SPACE, List.of(unit("u").capturedAt(latest).build()));

    // when
    Freshness f = reader.read(AccessScope.singleUser(), SPACE);

    // then
    assertThat(f.stale()).isEqualTo(stale);
  }

  @Test
  @DisplayName("공간의 최신 capturedAt(최댓값)을 쓴다")
  void usesLatestAcrossUnits() {
    // given
    units.upsert(
        SPACE,
        List.of(
            unit("a").capturedAt("2026-10-01T00:00:00.000Z").build(),
            unit("b").capturedAt("2026-10-13T11:30:00.000Z").build()));

    // when
    Freshness f = reader.read(AccessScope.singleUser(), SPACE);

    // then
    assertThat(f.latestCapturedAt()).contains("2026-10-13T11:30:00.000Z");
    assertThat(f.staleHours()).contains(0.5);
  }

  @Test
  @DisplayName("유닛이 없으면 UNAVAILABLE이다")
  void emptySpaceIsUnavailable() {
    // when
    Freshness f = reader.read(AccessScope.singleUser(), SPACE);

    // then
    assertThat(f).isEqualTo(Freshness.UNAVAILABLE);
    assertThat(f.staleHours()).isEqualTo(Optional.empty());
  }

  @Test
  @DisplayName("capturedAt을 파싱할 수 없으면(오프셋 없는 문자열 포함, F-4) UNAVAILABLE이다")
  void unparsableIsUnavailable() {
    // given
    units.upsert(SPACE, List.of(unit("u").capturedAt("2026-10-13T11:00:00").build()));

    // when
    Freshness f = reader.read(AccessScope.singleUser(), SPACE);

    // then
    assertThat(f).isEqualTo(Freshness.UNAVAILABLE);
  }

  @Nested
  @DisplayName("제한 범위에서")
  class Restricted {
    final FreshnessReaderScenario scenario = new FreshnessReaderScenario();

    @Test
    @DisplayName("빈 키는 확인 불가다")
    void emptyKeysAreUnavailable() {
      // given
      scenario.recordSuccess(SLACK, RECENT);

      // when
      var result = scenario.read(Set.of());

      // then
      assertThat(result).isEqualTo(Freshness.UNAVAILABLE);
    }

    @Test
    @DisplayName("숨은 문서의 더 새 capturedAt은 최신성에 나오지 않는다")
    void hiddenCapturedAtIsNotExposed() {
      // given
      scenario.recordSuccess(SLACK, OLDEST);
      scenario.recordSuccess(DRIVE, RECENT);
      scenario.recordHiddenCapturedAt(NOW);

      // when
      var result = scenario.read(BOTH_SOURCE_KEYS);

      // then
      assertThat(result.latestCapturedAt()).contains(OLDEST.toString());
    }

    @Test
    @DisplayName("최신성 포트 없이 조립되면 확인 불가다")
    void missingFreshnessPortIsUnavailable() {
      // given
      scenario.recordSuccess(SLACK, RECENT);
      scenario.recordHiddenCapturedAt(NOW);

      // when
      var result = scenario.readWithoutSources(BOTH_SOURCE_KEYS);

      // then
      assertThat(result).isEqualTo(Freshness.UNAVAILABLE);
    }

    @Test
    @DisplayName("유지보수 포트를 호출하지 않는다")
    void restrictedDoesNotReadMaintenance() {
      // given
      scenario.recordSuccess(SLACK, OLDEST);
      scenario.recordSuccess(DRIVE, RECENT);

      // when
      scenario.read(BOTH_SOURCE_KEYS);

      // then
      assertThat(scenario.maintenanceReads()).isZero();
    }

    @Test
    @DisplayName("유지보수 포트가 없어도 성공 수집 시각을 낸다")
    void restrictedNeedsNoMaintenance() {
      // given
      scenario.recordSuccess(SLACK, OLDEST);
      scenario.recordSuccess(DRIVE, RECENT);

      // when
      var result = scenario.readWithoutMaintenance(BOTH_SOURCE_KEYS);

      // then
      assertThat(result.latestCapturedAt()).contains(OLDEST.toString());
    }

    @Test
    @DisplayName("성공 run이 없는 원천이 섞이면 확인 불가다")
    void missingSourceSuccessIsUnavailable() {
      // given
      scenario.recordSuccess(SLACK, RECENT);

      // when
      var result = scenario.read(BOTH_SOURCE_KEYS);

      // then
      assertThat(result).isEqualTo(Freshness.UNAVAILABLE);
    }

    @Test
    @DisplayName("요청자 키의 원천 중 가장 오래된 성공 시각을 낸다")
    void oldestSourceDeterminesFreshness() {
      // given
      scenario.recordSuccess(SLACK, RECENT);
      scenario.recordSuccess(DRIVE, OLDEST);

      // when
      var result = scenario.read(BOTH_SOURCE_KEYS);

      // then
      assertThat(result)
          .isEqualTo(
              new Freshness(
                  Optional.of(OLDEST.toString()), Optional.of(STALE_BOUNDARY_HOURS), true));
    }
  }

  @Test
  @DisplayName("유지보수 포트 없이 조립된 무제한 리더는 확인 불가다")
  void unrestrictedWithoutMaintenanceIsUnavailable() {
    // given
    var scenario = new FreshnessReaderScenario();
    scenario.recordSuccess(SLACK, RECENT);

    // when
    var result = scenario.readUnrestrictedWithoutMaintenance();

    // then
    assertThat(result).isEqualTo(Freshness.UNAVAILABLE);
  }
}
