package io.scos.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("접근 건강 지표는 source label만 사용한다(O-2·O-3)")
class AccessGaugesTest {
  private final AccessGaugeScenario scenario = new AccessGaugeScenario();

  @ParameterizedTest
  @CsvSource({
    "scos.access.stale,3",
    "scos.access.incomplete_streak,2",
    "scos.access.documents_hidden,4",
    "scos.access.acl_unreadable,1",
    "scos.access.last_complete_age,60"
  })
  @DisplayName("소스 상태를 스크레이프 시점 지표로 연결한다")
  void statusValuesAreExposed(String name, double expected) {
    scenario.bind(true, true);
    // then
    assertThat(scenario.value(name)).isEqualTo(expected);
  }

  @Test
  @DisplayName("1초 안의 지표들은 같은 소스 상태 스냅샷을 공유한다")
  void currentScrapeKeepsOneSnapshot() {
    scenario.bind(true, true);
    scenario.value("scos.access.stale");
    scenario.changeStaleCount(7);
    // then
    assertThat(scenario.value("scos.access.stale")).isEqualTo(3);
  }

  @Test
  @DisplayName("다음 스크레이프는 변경된 소스 상태를 다시 읽는다")
  void nextScrapeRefreshesSourceState() {
    scenario.bind(true, true);
    scenario.value("scos.access.stale");
    scenario.changeStaleCount(7);
    scenario.nextScrape();
    // then
    assertThat(scenario.value("scos.access.stale")).isEqualTo(7);
  }

  @Test
  @DisplayName("문서·사용자 id label은 생성하지 않는다")
  void labelsContainOnlySource() {
    scenario.bind(true, true);
    // then
    assertThat(scenario.tagKeys()).containsExactly("source");
  }

  @Test
  @DisplayName("접근 수집이 꺼지면 지표를 등록하지 않는다")
  void disabledCollectionRegistersNoMetrics() {
    scenario.bind(false, true);
    // then
    assertThat(scenario.registry.getMeters()).isEmpty();
  }

  @Test
  @DisplayName("소스 상태를 알 수 없으면 신선한 0으로 보고하지 않는다")
  void absentStatusReportsNaN() {
    scenario.bind(true, false);
    // then
    assertThat(scenario.value("scos.access.stale")).isNaN();
  }

  @Test
  @DisplayName("다음 스크레이프의 완료 나이는 흐른 시각을 반영한다")
  void nextScrapeUsesCurrentTime() {
    scenario.bind(true, true);
    scenario.value("scos.access.last_complete_age");
    scenario.nextScrape();
    // then
    assertThat(scenario.value("scos.access.last_complete_age")).isEqualTo(61);
  }
}
