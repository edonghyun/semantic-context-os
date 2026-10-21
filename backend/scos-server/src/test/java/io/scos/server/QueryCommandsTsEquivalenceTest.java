package io.scos.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

/**
 * 조회 명령(source list·status·rejections, raw list·get)은 TS source-sync-cli와 같은 바이트를 낸다. 기대값은 TS 생성기가
 * 같은 각본(소스 셋, run 셋, 점유 하나)을 TS 저장소에 만들고 CLI를 돌려 얻은 출력이다(harness/fixtures/ops/expected-cli.json).
 * Java는 각본을 PostgreSQL에서 재현한다.
 */
@Tag("db")
@DisplayName("조회 명령은 TS source-sync-cli와 같은 바이트를 낸다(TS 골든 fixture)")
class QueryCommandsTsEquivalenceTest {
  static final JsonNode FIXTURE = QueryScenario.fixture();

  @Nested
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  @DisplayName("TS 조회 CLI 각본을 PostgreSQL에서 재현하면")
  class OnQueryScenario {
    QueryScenario scenario;

    static Stream<Arguments> cases() {
      List<Arguments> cases = new ArrayList<>();
      for (JsonNode c : FIXTURE.get("cases")) {
        List<String> args = new ArrayList<>();
        c.get("args").forEach(a -> args.add(a.asString()));
        List<String> output = new ArrayList<>();
        c.get("output").forEach(o -> output.add(o.asString()));
        cases.add(Arguments.of(String.join(" ", args), args, output));
      }
      return cases.stream();
    }

    @BeforeAll
    void replay() {
      scenario = QueryScenario.replay(FIXTURE.get("scenario"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("명령 출력이 TS 출력과 바이트 단위로 같다")
    void matchesTs(String command, List<String> args, List<String> expected) {
      // when
      List<String> output = scenario.run(args);

      // then
      assertThat(output).as(command).isEqualTo(expected);
    }
  }
}
