package io.scos.engine.internal.search.plan;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.engine.Effort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("노력 수준 프리셋은 수준별 검색 값을 정한다")
class PresetsTest {
  @Test
  @DisplayName("중간 수준은 지금의 기본 검색 값이다")
  void mediumIsTodaysDefault() {
    // when
    SearchSettings medium = Presets.of(Effort.MEDIUM);

    // then
    assertThat(medium).isEqualTo(SearchSettings.DEFAULT);
  }

  @Test
  @DisplayName("낮음 수준은 변형·링크를 끄고 후보 폭을 줄인다")
  void lowSkipsVariantsAndLinks() {
    // when
    SearchSettings low = Presets.of(Effort.LOW);

    // then
    assertThat(low.maxVariants()).isZero();
    assertThat(low.linksEnabled()).isFalse();
    assertThat(low)
        .extracting(SearchSettings::lexicalK, SearchSettings::vectorK)
        .containsExactly(20, 20);
  }

  @Test
  @DisplayName("높음 수준은 후보 폭과 링크 부모 창을 넓힌다")
  void highWidensCandidates() {
    // when
    SearchSettings high = Presets.of(Effort.HIGH);

    // then
    assertThat(high.lexicalK()).isEqualTo(100);
    assertThat(high.vectorK()).isEqualTo(100);
    assertThat(high.linkParentWindow()).isEqualTo(100);
  }

  @Test
  @DisplayName("재정렬은 낮음에서만 끈다")
  void rerankOffOnlyForLow() {
    // when·then
    assertThat(Presets.rerank(Effort.LOW)).isFalse();
    assertThat(Presets.rerank(Effort.MEDIUM)).isTrue();
    assertThat(Presets.rerank(Effort.HIGH)).isTrue();
  }
}
