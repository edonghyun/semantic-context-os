package io.scos.boardarchive.fetch;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.*;

@DisplayName("robots 규칙은 가장 구체적인 허용과 금지를 선택한다")
class RobotsRulesTest {
  @Test
  @DisplayName("가장 긴 일치가 우선한다")
  void longestMatchWins() {
    var r = RobotsRules.parse("User-agent: *\nDisallow: /a\nAllow: /a/b", "test");
    // then
    assertThat(r.allows("/a/b/c")).isTrue();
    assertThat(r.allows("/a/c")).isFalse();
  }

  @Test
  @DisplayName("달러 기호는 경로 끝에만 일치한다")
  void dollarAnchorsEnd() {
    var r = RobotsRules.parse("User-agent: *\nDisallow: /*.pdf$", "test");
    // then
    assertThat(r.allows("/a.pdf")).isFalse();
    assertThat(r.allows("/a.pdf?download")).isTrue();
  }

  @Test
  @DisplayName("지정 UA 그룹이 전체 그룹보다 우선한다")
  void specificAgentGroupOverridesStar() {
    var r =
        RobotsRules.parse("User-agent: *\nDisallow: /\nUser-agent: Archive\nAllow: /", "Archive/1");
    // then
    assertThat(r.allows("/a")).isTrue();
  }
}
