package io.scos.collection.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.spi.access.DocumentAcl.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("재관측하지 않는 지원 소스는 필요한 carrier만 보충한다(§5.2)")
class AccessTargetSelectionTest {
  private final AccessCollectionScenario scenario =
      new AccessCollectionScenario("custom-nonrefresh");

  @BeforeEach
  void givenCarrier() {
    scenario.active("file:F1", "gdrive.file");
  }

  @AfterEach
  void close() {
    scenario.close();
  }

  @Test
  @DisplayName("아직 관측하지 않은 carrier는 보충 대상으로 선정한다")
  void unobservedCarrierIsSelected() {
    // when
    scenario.collect();
    // then
    assertThat(scenario.targets()).containsExactly("file:F1");
  }

  @ParameterizedTest(name = "이전 상태 {0}")
  @ValueSource(strings = {"UNKNOWN", "FAILED"})
  @DisplayName("UNKNOWN 또는 FAILED 관측은 다시 보충한다")
  void unknownOrFailedCarrierIsSelected(String status) {
    // given
    scenario.withAcl("file:F1", Status.valueOf(status));
    // when
    scenario.collect();
    // then
    assertThat(scenario.targets()).containsExactly("file:F1");
  }

  @Test
  @DisplayName("본문 관측이 없는 기존 OK carrier는 보충에서 제외한다")
  void knownOkWithoutBodyObservationIsSkipped() {
    // given
    scenario.withAcl("file:F1", Status.OK);
    // when
    scenario.collect();
    // then
    assertThat(scenario.targets()).isEmpty();
  }

  @Test
  @DisplayName("이번 본문 run이 관측한 OK carrier는 다시 보충한다")
  void bodyObservedOkCarrierIsSelected() {
    // given
    scenario.withAcl("file:F1", Status.OK);
    scenario.bodyObserved("file:F1");
    // when
    scenario.collect();
    // then
    assertThat(scenario.targets()).containsExactly("file:F1");
  }
}
