package io.scos.spi.store;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("검색 필터는 접근 범위를 명시해야 한다")
class SearchFilterTest {
  @Test
  @DisplayName("접근 범위가 null이면 생성을 거부한다")
  void rejectsMissingAccess() {
    // when·then
    assertThatThrownBy(() -> new SearchFilter(false, null, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("access");
  }
}
