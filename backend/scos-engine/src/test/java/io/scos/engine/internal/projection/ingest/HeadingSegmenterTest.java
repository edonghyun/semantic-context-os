package io.scos.engine.internal.projection.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("헤딩 분할은 토큰·문자 한도와 유니코드 경계를 지킨다")
class HeadingSegmenterTest {
  @Test
  @DisplayName("4096자이며 토큰 한도 안인 본문은 하나의 구역이다")
  void keepsExactCharacterLimit() {
    // given
    String body = "x".repeat(4096);
    var scenario = new HeadingScenario(800);
    // when
    List<Segment> segments = scenario.split(body);
    // then
    assertThat(segments).extracting(Segment::content).containsExactly(body);
  }

  @Test
  @DisplayName("토큰 수가 상한과 같으면 본문을 나누지 않는다")
  void keepsExactTokenLimit() {
    // given
    var scenario = new HeadingScenario(2);
    // when
    List<Segment> segments = scenario.split("hello world");
    // then
    assertThat(segments).extracting(Segment::content).containsExactly("hello world");
  }

  @Test
  @DisplayName("긴 문단의 앞 사분점에 개행이 있으면 그 뒤에서 나눈다")
  void splitsAtQuarterBoundaryNewline() {
    // given
    var scenario = new HeadingScenario(2);
    // when
    List<Segment> segments = scenario.split("ab\ncccccc");
    // then
    assertThat(segments).extracting(Segment::content).containsExactly("ab\n", "cccccc");
  }

  @Test
  @DisplayName("반분 지점의 서로게이트 쌍은 같은 조각에 둔다")
  void keepsSurrogatePairTogether() {
    // given
    var scenario = new HeadingScenario(1);
    // when
    List<Segment> segments = scenario.split("ab😀cd");
    // then
    assertThat(segments).extracting(Segment::content).containsExactly("ab", "😀", "cd");
  }

  @Test
  @DisplayName("첫 헤딩 앞의 빈 줄은 별도 구역을 만들지 않는다")
  void omitsBlankPreamble() {
    // given
    var scenario = new HeadingScenario(800);
    // when
    List<Segment> segments = scenario.split(" \n\n# 제목\n본문");
    // then
    assertThat(segments).extracting(Segment::content).containsExactly("# 제목\n본문");
  }
}
