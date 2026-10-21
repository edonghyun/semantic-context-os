package io.scos.connector.notion.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.scos.connector.notion.NotionAdapter;
import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.CallFailureException;
import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.connector.DeletionDetection;
import io.scos.spi.connector.SourceConfig;
import java.time.Clock;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

/**
 * Notion 소스 설정(TS readNotionConfiguration·collectionCoverage, 표 expected-client.json configCases):
 * 받는 설정과 첫 run의 coverage, 거부하는 설정과 그 메시지가 TS와 같다.
 */
@DisplayName("Notion 소스 설정은 TS readNotionConfiguration 표와 같다")
class NotionConfigurationTest {
  static final NotionAdapter ADAPTER =
      new NotionAdapter(ref -> "unused", null, NotionFixtures.JSON, Clock.systemUTC());

  static Stream<Arguments> rows(boolean accepted) {
    return StreamSupport.stream(NotionFixtures.client().get("configCases").spliterator(), false)
        .filter(row -> row.get("accepted").asBoolean() == accepted)
        .map(row -> Arguments.of(row.get("label").asString(), row));
  }

  static Stream<Arguments> acceptedRows() {
    return rows(true);
  }

  static Stream<Arguments> rejectedRows() {
    return rows(false);
  }

  static SourceConfig configOf(JsonNode row) {
    JsonNode config = row.get("config");
    return new SourceConfig(config.get("schemaVersion").asInt(), config.get("value").toString());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("acceptedRows")
  @DisplayName("TS가 받는 설정은 체크포인트 없는 run의 coverage가 TS와 같다")
  void acceptedConfigCoverageMatchesTs(String label, JsonNode row) {
    // when
    CollectionCoverage coverage =
        ADAPTER.coverage(configOf(row), CollectionMode.INCREMENTAL, Optional.empty());

    // then
    assertThat(coverage.wire()).isEqualTo(row.get("coverage").asString());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("acceptedRows")
  @DisplayName("TS가 받는 설정의 삭제 감지 수준이 TS deletionDetection과 같다")
  void deletionDetectionMatchesTs(String label, JsonNode row) {
    // when
    DeletionDetection detection = ADAPTER.deletionDetection(configOf(row));

    // then
    assertThat(detection.wire()).isEqualTo(row.get("deletionDetection").asString());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("rejectedRows")
  @DisplayName("TS가 거부하는 설정은 invalid_configuration으로 같은 메시지와 함께 거부한다")
  void rejectsLikeTs(String label, JsonNode row) {
    // when
    Throwable thrown = catchThrowable(() -> ADAPTER.validateConfig(configOf(row)));

    // then
    assertThat(thrown)
        .isInstanceOfSatisfying(
            CallFailureException.class,
            e ->
                assertThat(e.failure())
                    .extracting(CallFailure::kind, CallFailure::message)
                    .containsExactly(
                        CallFailure.Kind.INVALID_CONFIGURATION, row.get("message").asString()));
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(CollectionMode.class)
  @DisplayName("coverage는 수집 모드와 무관하다")
  void coverageIgnoresMode(CollectionMode mode) {
    // when
    CollectionCoverage coverage =
        ADAPTER.coverage(
            new SourceConfig(1, NotionFixtures.config("roots")), mode, Optional.empty());

    // then
    assertThat(coverage).isEqualTo(CollectionCoverage.COMPLETE_SNAPSHOT);
  }
}
