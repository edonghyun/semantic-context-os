package io.scos.connector.notion.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.scos.connector.notion.internal.NotionScenario.Collection;
import io.scos.connector.notion.internal.NotionTsRuns.RequestView;
import io.scos.engine.support.text.JsJson;
import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.CollectionCoverage;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

/**
 * 체크포인트 읽기 호환(F12, TS 표 expected-client.json checkpointCases): 저장된 체크포인트에서 시작하는 수집의 첫 공급자 요청 또는 요청
 * 전 거부 메시지, 그리고 그 run의 coverage가 TS와 같다. roots 모드는 v3, v1·v2(검색 모드 시절)는 초기 시드로 읽는다.
 */
@DisplayName("Notion 저장 체크포인트는 TS 표와 같이 읽고 거부한다(F12)")
class NotionCheckpointTest {
  final NotionScenario notion = new NotionScenario();

  @AfterEach
  void tearDown() {
    notion.close();
  }

  static Stream<Arguments> rows(Predicate<JsonNode> which) {
    return StreamSupport.stream(NotionFixtures.client().get("checkpointCases").spliterator(), false)
        .filter(which)
        .map(row -> Arguments.of(row.get("label").asString(), row));
  }

  static Optional<Checkpoint> checkpointOf(JsonNode row) {
    JsonNode c = row.get("checkpoint");
    return c.isNull()
        ? Optional.empty()
        : Optional.of(
            new Checkpoint(c.get("schemaVersion").asInt(), JsJson.stringify(c.get("value"))));
  }

  static Stream<Arguments> rejectedRows() {
    return rows(row -> row.has("error"));
  }

  static Stream<Arguments> requestingRows() {
    return rows(row -> row.has("firstRequest") && !row.get("firstRequest").isNull());
  }

  static Stream<Arguments> silentRows() {
    return rows(row -> row.has("firstRequest") && row.get("firstRequest").isNull());
  }

  static Stream<Arguments> coverageRows() {
    return rows(row -> row.get("coverage").has("value"));
  }

  static Stream<Arguments> coverageErrorRows() {
    return rows(row -> row.get("coverage").has("error"));
  }

  @Nested
  @DisplayName("TS가 거부하는 체크포인트에서 수집하면")
  class Rejected {
    @ParameterizedTest(name = "{0}")
    @MethodSource("io.scos.connector.notion.internal.NotionCheckpointTest#rejectedRows")
    @DisplayName("같은 메시지로 실패한다")
    void rejectsLikeTs(String label, JsonNode row) {
      // when
      Collection java =
          notion.collect(row.get("config").get("value").toString(), checkpointOf(row));

      // then
      assertThat(java.failure())
          .hasValueSatisfying(
              f ->
                  assertThat(f)
                      .isInstanceOf(NotionProtocolException.class)
                      .hasMessage(row.get("error").asString()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("io.scos.connector.notion.internal.NotionCheckpointTest#rejectedRows")
    @DisplayName("공급자를 부르지 않는다")
    void rejectsBeforeRequests(String label, JsonNode row) {
      // when
      notion.collect(row.get("config").get("value").toString(), checkpointOf(row)).requireFailed();

      // then
      assertThat(notion.requests()).isEmpty();
    }
  }

  @Nested
  @DisplayName("TS가 받는 체크포인트에서 수집하면")
  class Accepted {
    @ParameterizedTest(name = "{0}")
    @MethodSource("io.scos.connector.notion.internal.NotionCheckpointTest#requestingRows")
    @DisplayName("TS와 같은 첫 공급자 요청으로 이어 간다")
    void resumesWithSameFirstRequest(String label, JsonNode row) {
      // given
      RequestView expected = notion.stopAt(row.get("firstRequest"));

      // when
      notion.collect(row.get("config").get("value").toString(), checkpointOf(row));

      // then
      assertThat(notion.requests()).first().isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("io.scos.connector.notion.internal.NotionCheckpointTest#silentRows")
    @DisplayName("남은 작업이 옛 휴지통 행 작업뿐이면 요청하지 않는다")
    void finishesCycleWithoutRequests(String label, JsonNode row) {
      // when
      notion.collect(row.get("config").get("value").toString(), checkpointOf(row));

      // then
      assertThat(notion.requests()).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("io.scos.connector.notion.internal.NotionCheckpointTest#silentRows")
    @DisplayName("옛 휴지통 행 작업 하나는 페이지 하나로 사이클을 끝낸다")
    void finishesCycleInOnePage(String label, JsonNode row) {
      // when
      Collection java =
          notion.collect(row.get("config").get("value").toString(), checkpointOf(row));

      // then
      assertThat(java.pageViews()).hasSize(1);
    }
  }

  @Nested
  @DisplayName("저장된 체크포인트로 coverage를 물으면")
  class Coverage {
    @ParameterizedTest(name = "{0}")
    @MethodSource("io.scos.connector.notion.internal.NotionCheckpointTest#coverageRows")
    @DisplayName("TS collectionCoverage와 같다")
    void matchesTs(String label, JsonNode row) {
      // when
      CollectionCoverage coverage =
          notion.coverage(row.get("config").get("value"), checkpointOf(row));

      // then
      assertThat(coverage.wire()).isEqualTo(row.get("coverage").get("value").asString());
    }

    /** TS처럼 coverage에서 던진다 — 런타임은 run 없이 점유를 풀고 전파한다(CONTRACTS F17-D1, 공용 런타임 560895e). */
    @ParameterizedTest(name = "{0}")
    @MethodSource("io.scos.connector.notion.internal.NotionCheckpointTest#coverageErrorRows")
    @DisplayName("TS가 coverage에서 거부하는 체크포인트면 같은 메시지로 던진다")
    void unreadableCheckpointThrowsLikeTs(String label, JsonNode row) {
      // when
      Throwable thrown =
          catchThrowable(() -> notion.coverage(row.get("config").get("value"), checkpointOf(row)));

      // then
      assertThat(thrown)
          .isInstanceOf(NotionProtocolException.class)
          .hasMessage(row.get("coverage").get("error").asString());
    }
  }
}
