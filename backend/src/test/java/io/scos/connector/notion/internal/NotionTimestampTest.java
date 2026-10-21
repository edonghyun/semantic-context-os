package io.scos.connector.notion.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.Set;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

/**
 * 편집 시각 정규화(TS normalizedTimestamp — `new Date(v).toISOString()`, 표 expected-client.json
 * timestampCases): 원본 순서 position이 TS와 같고, TS가 읽지 못하는 값은 같은 메시지로 실패한다. 오프셋 없는 시각은 TS가 프로세스 지역 시각으로
 * 읽으므로 Java는 받지 않는다(CONTRACTS F17-D2).
 */
@DisplayName("Notion 편집 시각은 TS normalizedTimestamp처럼 정규화한다(F17-D2)")
class NotionTimestampTest {
  /** 의도한 차이(CONTRACTS F17-D2): TS는 받고 Java는 거부한다. */
  static final Set<String> DEVIATIONS = Set.of("오프셋 없는 시각");

  static Stream<Arguments> rows(boolean readable) {
    return StreamSupport.stream(NotionFixtures.client().get("timestampCases").spliterator(), false)
        .filter(row -> !DEVIATIONS.contains(row.get("label").asString()))
        .filter(row -> row.get("error").isNull() == readable)
        .map(row -> Arguments.of(row.get("label").asString(), row));
  }

  static Stream<Arguments> readableRows() {
    return rows(true);
  }

  static Stream<Arguments> unreadableRows() {
    return rows(false);
  }

  static Stream<Arguments> deviationRows() {
    return StreamSupport.stream(NotionFixtures.client().get("timestampCases").spliterator(), false)
        .filter(row -> DEVIATIONS.contains(row.get("label").asString()))
        .map(row -> Arguments.of(row.get("label").asString(), row));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("readableRows")
  @DisplayName("TS가 읽는 시각은 같은 밀리초 UTC 표기가 된다")
  void normalizesLikeTs(String label, JsonNode row) {
    // when
    String position = NotionMutations.position(row.get("value").asString());

    // then
    assertThat(position).isEqualTo(row.get("position").asString());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("unreadableRows")
  @DisplayName("TS가 읽지 못하는 시각은 같은 메시지의 Error다")
  void rejectsLikeTs(String label, JsonNode row) {
    // when
    Throwable thrown = catchThrowable(() -> NotionMutations.position(row.get("value").asString()));

    // then
    assertThat(thrown)
        .isInstanceOf(NotionProtocolException.class)
        .hasMessage(row.get("error").asString());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("deviationRows")
  @DisplayName("오프셋 없는 시각은 TS와 달리 읽지 못하는 시각의 Error다")
  void rejectsLocalTime(String label, JsonNode row) {
    // when
    Throwable thrown = catchThrowable(() -> NotionMutations.position(row.get("value").asString()));

    // then
    assertThat(thrown)
        .isInstanceOf(NotionProtocolException.class)
        .hasMessage("Invalid Notion page timestamp");
  }
}
