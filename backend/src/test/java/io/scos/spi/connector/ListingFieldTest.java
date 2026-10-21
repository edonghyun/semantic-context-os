package io.scos.spi.connector;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** 소스 목록 필드(stage2 §2.4): 이름은 목록 항목의 키라 비울 수 없다. 값 검사(JSON인가)는 목록을 만드는 런타임이 한다. */
@DisplayName("목록 필드는 이름이 있어야 만들어진다(stage2 §2.4)")
class ListingFieldTest {
  @ParameterizedTest(name = "[{0}]")
  @NullAndEmptySource
  @ValueSource(strings = {" "})
  @DisplayName("이름이 비어 있으면 만들기를 거부한다")
  void rejectsBlankName(String name) {
    // when·then
    assertThatThrownBy(() -> new ListingField(name, "{}"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
