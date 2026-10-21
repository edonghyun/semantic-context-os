package io.scos.connector.notion.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.connector.notion.internal.NotionScenario.Collection;
import io.scos.connector.notion.internal.NotionTsRuns.TsRun;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

/**
 * 운영 실패 유형과 프로토콜 위반(TS 표 expected-failures.json, TESTING §3.6 3층): 같은 장애 각본에서 Java 어댑터가 실패 전까지 낸
 * 페이지, 보낸 요청, run에 남을 errorKind가 TS와 같다. 운영 Notion run 실패의 분류는 F6-report에 있다(시간 초과·전송 실패·JSON 아닌
 * 게이트웨이 오류·internal_server_error). 장애 run은 빠른 시도 timeout으로 돈다.
 */
@DisplayName("Notion 운영 실패·프로토콜 위반은 TS와 같은 곳에서 멈춘다(§3.6 3층)")
class NotionFailureTest {
  final NotionScenario notion = new NotionScenario();

  @AfterEach
  void tearDown() {
    notion.close();
  }

  static Stream<Arguments> cases() {
    return StreamSupport.stream(
            NotionFixtures.read("expected-failures.json").get("cases").spliterator(), false)
        .map(c -> Arguments.of(c.get("label").asString(), c));
  }

  static Stream<Arguments> reachableCases() {
    return cases().filter(a -> !((JsonNode) a.get()[1]).get("transportDown").asBoolean());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  @DisplayName("실패 전까지 TS와 같은 페이지를 낸다")
  void pagesMatchTs(String label, JsonNode failure) {
    // given
    notion.failureCase(failure);

    // when
    Collection java = notion.collect(NotionScenario.Config.of(failure.get("config")));

    // then
    assertThat(java.pageViews()).containsExactlyElementsOf(TsRun.of(failure).pages());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  @DisplayName("run errorKind가 TS 저장값과 같다")
  void errorKindMatchesTs(String label, JsonNode failure) {
    // given
    notion.failureCase(failure);

    // when
    Collection java = notion.collect(NotionScenario.Config.of(failure.get("config")));

    // then
    JsonNode ts = failure.get("run").get("errorKind");
    assertThat(java.errorKind())
        .isEqualTo(ts.isNull() ? Optional.empty() : Optional.of(ts.asString()));
  }

  /** 전송 실패는 요청이 서버에 닿지 않아 셀 수 없다(TS 가짜 fetch는 시도마다 기록한다). */
  @ParameterizedTest(name = "{0}")
  @MethodSource("reachableCases")
  @DisplayName("재시도를 포함해 TS와 같은 요청을 보낸다")
  void requestsMatchTs(String label, JsonNode failure) {
    // given
    notion.failureCase(failure);

    // when
    notion.collect(NotionScenario.Config.of(failure.get("config")));

    // then
    assertThat(notion.requests()).containsExactlyElementsOf(TsRun.of(failure).requests());
  }
}
