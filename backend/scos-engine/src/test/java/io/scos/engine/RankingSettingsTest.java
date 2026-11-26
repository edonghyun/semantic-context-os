package io.scos.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.engine.RankingSettings.Recency;
import io.scos.engine.RankingSettings.Recency.When;
import io.scos.engine.RankingSettings.Sources;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("순위 설정은 만들 때 범위 밖 값과 겹치는 별명을 거부한다(CONTRACTS B13)")
class RankingSettingsTest {
  @ParameterizedTest
  @ValueSource(doubles = {-0.1, Double.NaN, Double.POSITIVE_INFINITY})
  @DisplayName("날짜 계수 세기는 0 이상의 유한수다")
  void rejectsBadRecencyWeight(double weight) {
    // when·then
    assertThatThrownBy(() -> new Recency(weight, 0.99, When.MARKED))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(doubles = {0, 1.01, -0.5, Double.NaN})
  @DisplayName("하루 보존율은 (0, 1] 안이다")
  void rejectsBadRetention(double retention) {
    // when·then
    assertThatThrownBy(() -> new Recency(0.1, retention, When.MARKED))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("반감기는 보존율 0.5^(1/일수)가 된다")
  void halfLifeBecomesRetention() {
    // when
    Recency recency = Recency.halfLife(0.2, 1, When.ALWAYS);
    // then
    assertThat(recency).isEqualTo(new Recency(0.2, 0.5, When.ALWAYS));
  }

  @Test
  @DisplayName("문서당 상한은 음수일 수 없다")
  void rejectsNegativePerDocument() {
    // when·then
    assertThatThrownBy(() -> new RankingSettings(Recency.DEFAULT, -1, 2, Sources.NONE))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("current 창은 1 이상이다")
  void rejectsZeroCurrentWindow() {
    // when·then
    assertThatThrownBy(() -> new RankingSettings(Recency.DEFAULT, 2, 0, Sources.NONE))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("같은 별명은 두 원천에 둘 수 없다")
  void rejectsAliasSharedBySources() {
    // when·then
    assertThatThrownBy(
            () -> new Sources(Map.of("a", Set.of("알파"), "b", Set.of("ALPHA", "알파")), Set.of(), 1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("색인 원천의 별명은 미수집 별명과 겹칠 수 없다(대소문자 무관)")
  void rejectsAliasAlsoNotIndexed() {
    // when·then
    assertThatThrownBy(() -> new Sources(Map.of("a", Set.of("Alpha")), Set.of("alpha"), 1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("빈 별명은 거부한다")
  void rejectsBlankAlias() {
    // when·then
    assertThatThrownBy(() -> new Sources(Map.of("a", Set.of(" ")), Set.of(), 1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("기본값은 지금 상수와 같다: 0.1·0.99·시간 낱말일 때, 문서당 2, 창 2, 별명 없음")
  void defaultMatchesTheFormerConstants() {
    // then
    assertThat(RankingSettings.DEFAULT)
        .isEqualTo(
            new RankingSettings(
                new Recency(0.1, 0.99, When.MARKED), 2, 2, new Sources(Map.of(), Set.of(), 0)));
  }
}
