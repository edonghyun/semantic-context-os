package io.scos.connector.googledrive.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.CallFailureException;
import io.scos.spi.connector.SourceConfig;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

/**
 * Drive 소스 설정(TS configuration): TS validateConfig가 받거나 거부하는 표(expected-cases.json configCases)와
 * 같다.
 */
@DisplayName("Drive 소스 설정은 TS validateConfig 표와 같다")
class GoogleDriveConfigurationTest {
  static Stream<Arguments> configCases(boolean accepted) {
    return StreamSupport.stream(GoogleDriveFixtures.cases().get("configCases").spliterator(), false)
        .filter(c -> c.get("accepted").asBoolean() == accepted)
        .map(c -> Arguments.of(c.get("label").asString(), c));
  }

  static Stream<Arguments> acceptedCases() {
    return configCases(true);
  }

  static Stream<Arguments> rejectedCases() {
    return configCases(false);
  }

  static SourceConfig config(JsonNode c) {
    return new SourceConfig(c.get("schemaVersion").asInt(), c.get("value").toString());
  }

  /** 받으면 null, 거부하면 TS 메시지. */
  static String outcome(SourceConfig config) {
    Throwable thrown =
        catchThrowable(() -> GoogleDriveConfiguration.read(GoogleDriveFixtures.JSON, config));
    return thrown == null ? null : ((CallFailureException) thrown).failure().message();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("acceptedCases")
  @DisplayName("TS가 받는 설정을 받는다")
  void acceptsLikeTs(String label, JsonNode c) {
    // when
    String result = outcome(config(c));

    // then
    assertThat(result).isNull();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("rejectedCases")
  @DisplayName("TS가 거부하는 설정을 같은 메시지로 거부한다")
  void rejectsLikeTs(String label, JsonNode c) {
    // when
    String result = outcome(config(c));

    // then
    assertThat(result).isEqualTo(c.get("message").asString());
  }

  @Test
  @DisplayName("거부는 재시도하지 않는 invalid_configuration이다(계정 없음)")
  void rejectionIsInvalidConfiguration() {
    // when
    Throwable thrown =
        catchThrowable(
            () ->
                GoogleDriveConfiguration.read(GoogleDriveFixtures.JSON, new SourceConfig(1, "{}")));

    // then
    assertThat(thrown)
        .isInstanceOfSatisfying(
            CallFailureException.class,
            e -> assertThat(e.failure().kind()).isEqualTo(CallFailure.Kind.INVALID_CONFIGURATION));
  }

  @Test
  @DisplayName("계정은 소문자로 둔다")
  void lowercasesAccount() {
    // when
    GoogleDriveConfiguration config =
        GoogleDriveConfiguration.read(
            GoogleDriveFixtures.JSON,
            new SourceConfig(1, "{\"accountEmail\":\"Reader@Example.TEST\"}"));

    // then
    assertThat(config.accountEmail()).isEqualTo("reader@example.test");
  }

  @Test
  @DisplayName("다운로드 설정이 없으면 원본을 받는다")
  void downloadsByDefault() {
    // when
    GoogleDriveConfiguration config =
        GoogleDriveConfiguration.read(
            GoogleDriveFixtures.JSON, new SourceConfig(1, "{\"accountEmail\":\"a@b\"}"));

    // then
    assertThat(config.downloadBinaries()).isTrue();
  }
}
