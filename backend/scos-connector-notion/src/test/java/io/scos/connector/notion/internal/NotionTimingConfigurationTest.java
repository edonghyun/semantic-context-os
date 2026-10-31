package io.scos.connector.notion.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.connector.notion.internal.NotionConfiguration.Roots;
import io.scos.spi.connector.CallFailureException;
import io.scos.spi.connector.SourceConfig;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.node.ObjectNode;

@DisplayName("Notion roots v2 설정은 소스별 증분 시간 정책을 검증한다")
class NotionTimingConfigurationTest {
  static final String DEFAULT_ROOTS = "{\"rootPageIds\":[\"root\"]}";
  static final String CUSTOM_ROOTS =
      """
      {"rootPageIds":["root"],"incrementalIntervalSeconds":300,"overlapSeconds":600}
      """;

  @ParameterizedTest
  @MethodSource("acceptedTimings")
  @DisplayName("roots v2는 명시한 주기·겹침과 생략한 기본값을 읽는다")
  void readsSourceTiming(String value, Duration interval, Duration overlap) {
    var roots = (Roots) NotionConfiguration.read(NotionFixtures.JSON, new SourceConfig(2, value));
    // then
    assertThat(roots)
        .extracting(Roots::incrementalInterval, Roots::overlap)
        .containsExactly(interval, overlap);
  }

  @Test
  @DisplayName("공유 전체 검색 경로는 적용하지 않는 시간 키를 시작 전에 거부한다")
  void rejectsTimingForLegacySharedDiscovery() {
    var value = (ObjectNode) NotionFixtures.JSON.readTree(CUSTOM_ROOTS);
    value.put("discoverSharedPages", true);
    // then
    assertThatThrownBy(
            () ->
                NotionConfiguration.read(
                    NotionFixtures.JSON, new SourceConfig(2, value.toString())))
        .isInstanceOf(CallFailureException.class)
        .hasMessageContaining("discoverSharedPages");
  }

  @Test
  @DisplayName("겹침이 증분 주기보다 짧으면 두 키 이름을 포함해 시작 전에 거부한다")
  void rejectsOverlapShorterThanInterval() {
    var config = new SourceConfig(2, CUSTOM_ROOTS.replace("300", "1200"));
    // then
    assertThatThrownBy(() -> NotionConfiguration.read(NotionFixtures.JSON, config))
        .isInstanceOf(CallFailureException.class)
        .hasMessageContaining("overlapSeconds")
        .hasMessageContaining("incrementalIntervalSeconds");
  }

  @ParameterizedTest
  @MethodSource("invalidSeconds")
  @DisplayName("시간 값이 양의 정수 초가 아니면 시작 전에 해당 키 이름으로 거부한다")
  void rejectsInvalidSeconds(String key, String invalidValue) {
    var value = NotionFixtures.JSON.readTree(DEFAULT_ROOTS).deepCopy();
    ((ObjectNode) value).set(key, NotionFixtures.JSON.readTree(invalidValue));
    // then
    assertThatThrownBy(
            () ->
                NotionConfiguration.read(
                    NotionFixtures.JSON, new SourceConfig(2, value.toString())))
        .isInstanceOf(CallFailureException.class)
        .hasMessageContaining(key);
  }

  static Stream<Arguments> invalidSeconds() {
    return Stream.of("incrementalIntervalSeconds", "overlapSeconds")
        .flatMap(
            key ->
                Stream.of("0", "-1", "1.5", "\"1\"", "null", "true", "2147483648")
                    .map(value -> Arguments.of(key, value)));
  }

  static Stream<Arguments> acceptedTimings() {
    return Stream.of(
        Arguments.of(DEFAULT_ROOTS, Duration.ofMinutes(15), Duration.ofMinutes(30)),
        Arguments.of(CUSTOM_ROOTS, Duration.ofMinutes(5), Duration.ofMinutes(10)),
        Arguments.of(
            "{\"rootPageIds\":[\"root\"],\"incrementalIntervalSeconds\":300}",
            Duration.ofMinutes(5),
            Duration.ofMinutes(30)),
        Arguments.of(
            "{\"rootPageIds\":[\"root\"],\"incrementalIntervalSeconds\":600,\"overlapSeconds\":600}",
            Duration.ofMinutes(10),
            Duration.ofMinutes(10)));
  }
}
