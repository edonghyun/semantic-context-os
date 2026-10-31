package io.scos.connector.slack.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 같음은 공급자 글자, 앞뒤는 마이크로초: 두 기준이 갈리는 "1.5"와 "1.500000"으로 본다. */
@DisplayName("Slack 시각은 글자로 같음을, 마이크로초로 앞뒤를 본다")
class SlackTsTest {
  static final SlackTs SHORT = new SlackTs("1.5");
  static final SlackTs PADDED = new SlackTs("1.500000");

  @Test
  @DisplayName("자연 순서가 없어 같음과 어긋나는 정렬 컬렉션에 그냥 넣을 수 없다")
  void hasNoNaturalOrder() {
    // then
    assertThat(Comparable.class.isAssignableFrom(SlackTs.class)).isFalse();
  }

  @Test
  @DisplayName("글자가 다르면 같은 시각이라도 같지 않다")
  void equalityFollowsText() {
    // then
    assertThat(SHORT).isNotEqualTo(PADDED);
  }

  @Test
  @DisplayName("시간순 비교는 같은 시각의 다른 글자를 0으로 본다")
  void chronologicalIgnoresSpelling() {
    // then
    assertThat(SlackTs.CHRONOLOGICAL.compare(SHORT, PADDED)).isZero();
    assertThat(SlackTs.CHRONOLOGICAL.compare(new SlackTs("1.000009"), SHORT)).isNegative();
  }

  @Test
  @DisplayName("시간순 TreeSet은 같은 시각의 다른 글자를 하나로 합친다")
  void chronologicalTreeSetMergesSameInstant() {
    // given
    TreeSet<SlackTs> set = new TreeSet<>(SlackTs.CHRONOLOGICAL);

    // when
    set.add(SHORT);
    set.add(PADDED);

    // then
    assertThat(set).containsExactly(SHORT);
  }
}
