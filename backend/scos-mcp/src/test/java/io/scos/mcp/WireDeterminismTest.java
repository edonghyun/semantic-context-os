package io.scos.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.engine.support.text.DeterminismFixture;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

/** mcp 모듈의 결정론(CONTRACTS F-7 반올림): 응답 층 3자리 반올림이 TS Math.round(x·1000)/1000과 비트 동일. */
@DisplayName("응답 점수 3자리 반올림은 TS와 비트 동일하다(CONTRACTS F-7)")
class WireDeterminismTest {
  static Stream<Named<JsonNode>> roundingCases() {
    return DeterminismFixture.cases(DeterminismFixture.load().get("F7_rounding"), "input");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("roundingCases")
  @DisplayName("round3는 TS와 같다(half-up)")
  void round3MatchesTs(JsonNode c) {
    // when
    double rounded = McpTools.round3(c.get("input").asDouble());

    // then
    assertThat(rounded).isEqualTo(c.get("rounded").asDouble());
  }
}
