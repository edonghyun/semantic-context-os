package io.scos.engine.internal.projection;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.spi.projection.Tokenizer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * F-6(ADR-0019): 언어 중립 fixture(harness/fixtures/p4/tokens.json, TS js-tiktoken o200k_base가 센 값)와 토큰
 * 수가 전부 같다.
 */
@DisplayName("토큰 수는 TS js-tiktoken o200k_base와 같다(F-6, ADR-0019)")
class TokenizerFixtureTest {
  static JsonNode fixture() throws Exception {
    Path file =
        Path.of(
            System.getProperty("scos.fixtures.p4.dir", "../harness/fixtures/p4"), "tokens.json");
    return new ObjectMapper().readTree(Files.readString(file));
  }

  static final int MIN_CASES = 10;

  @Test
  @DisplayName("fixture는 o200k_base 인코딩으로 센 값이다")
  void fixtureUsesO200k() throws Exception {
    // when·then
    assertThat(fixture().get("encoding").asString()).isEqualTo("o200k_base");
  }

  @Test
  @DisplayName("빈 문자열·한국어·이모지·코드펜스·NBSP·긴 반복까지 토큰 수가 TS와 같다")
  void countsMatchTs() throws Exception {
    // given
    Tokenizer t = new JtokkitTokenizer();
    List<JsonNode> cases = new ArrayList<>();
    fixture().get("cases").forEach(cases::add);
    if (cases.size() < MIN_CASES)
      throw new IllegalStateException(
          "given 준비 실패: fixture 사례가 " + MIN_CASES + "건 이상이어야 한다 — " + cases.size());

    // when·then
    assertThat(cases)
        .allSatisfy(
            c ->
                assertThat(t.count(c.get("input").asString()))
                    .as(c.get("input").asString())
                    .isEqualTo(c.get("tokens").asInt()));
  }
}
