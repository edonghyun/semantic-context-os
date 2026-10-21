package io.scos.collection.internal.postgres;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** DB 종단 시나리오의 데이터셋 변경 어휘 자체 테스트(F15 C7): 바꿀 것이 없으면 조용히 넘어가지 않고 given 준비 실패로 멈춘다. */
@DisplayName("DB 종단 시나리오의 데이터셋 변경 어휘는 바꿀 것이 없으면 멈춘다(C7)")
@Tag("db")
class CollectionScenarioTest {
  final CollectionScenario collection = new CollectionScenario();

  @AfterEach
  void close() {
    collection.close();
  }

  @Test
  @DisplayName("ts 없는 메시지를 이미 지웠으면 다시 지우려 할 때 given 준비 실패로 멈춘다")
  void noRejectedItemsTwiceStops() {
    // given
    collection.givenNoRejectedItems();

    // when·then
    assertThatThrownBy(collection::givenNoRejectedItems)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("given 준비 실패: 데이터셋 C0000000002에 ts 없는 메시지가 없다");
  }

  @Test
  @DisplayName("없는 ts의 메시지를 지우려 하면 given 준비 실패로 멈춘다")
  void deletingUnknownMessageStops() {
    // when·then
    assertThatThrownBy(() -> collection.slackMessageDeleted("1760400999.000000"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("given 준비 실패: C0000000001에 ts 1760400999.000000 메시지가 없다");
  }
}
