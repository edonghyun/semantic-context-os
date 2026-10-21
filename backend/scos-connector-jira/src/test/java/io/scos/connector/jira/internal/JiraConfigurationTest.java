package io.scos.connector.jira.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

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

/**
 * Jira 소스 설정(TS readJiraConfiguration): 받는 설정과 거부 메시지는 TS가 만든 표(expected-client.json configCases)와
 * 같다. 인증정보를 보낼 곳이므로 siteUrl은 Atlassian Cloud 사이트 origin만 받는다.
 */
@DisplayName("Jira 소스 설정은 TS readJiraConfiguration 표와 같이 받고 거부한다")
class JiraConfigurationTest {
  static final String SITE = "https://synthetic.atlassian.net";
  static final String CLOUD_ID = "0a1b2c3d-0000-4000-8000-00000000c10d";

  static SourceConfig config(String valueJson) {
    return new SourceConfig(1, valueJson);
  }

  static Stream<Arguments> cases(boolean accepted) {
    return StreamSupport.stream(JiraFixtures.client().get("configCases").spliterator(), false)
        .filter(c -> c.get("accepted").asBoolean() == accepted)
        .map(
            c ->
                Arguments.of(
                    c.get("label").asString(),
                    new SourceConfig(
                        c.get("schemaVersion").asInt(),
                        JiraFixtures.JSON.writeValueAsString(c.get("value"))),
                    accepted ? c.get("origin").asString() : c.get("message").asString()));
  }

  static Stream<Arguments> acceptedCases() {
    return cases(true);
  }

  static Stream<Arguments> rejectedCases() {
    return cases(false);
  }

  static JiraConfiguration read(SourceConfig config) {
    return JiraConfiguration.read(JiraFixtures.JSON, config);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("acceptedCases")
  @DisplayName("받는 설정의 siteUrl은 TS처럼 origin으로 정규화된다")
  void normalizesSiteUrlToOrigin(String label, SourceConfig config, String origin) {
    // when
    JiraConfiguration jira = read(config);

    // then
    assertThat(jira.siteUrl()).isEqualTo(origin);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("rejectedCases")
  @DisplayName("거부하는 설정은 TS와 같은 메시지의 invalid_configuration이다")
  void rejectsWithTsMessage(String label, SourceConfig config, String message) {
    // when
    CallFailureException thrown =
        catchThrowableOfType(CallFailureException.class, () -> read(config));

    // then
    assertThat(thrown.failure())
        .extracting(CallFailure::kind, CallFailure::message)
        .containsExactly(CallFailure.Kind.INVALID_CONFIGURATION, message);
  }

  @Test
  @DisplayName("downloadAttachments가 없으면 첨부를 내려받는다")
  void downloadsAttachmentsByDefault() {
    // when
    JiraConfiguration jira =
        read(config("{\"siteUrl\":\"" + SITE + "\",\"email\":\"a@example.test\"}"));

    // then
    assertThat(jira.downloadAttachments()).isTrue();
  }

  @Test
  @DisplayName("downloadAttachments가 false면 첨부를 내려받지 않는다")
  void skipsAttachmentsWhenDisabled() {
    // when
    JiraConfiguration jira =
        read(
            config(
                "{\"siteUrl\":\""
                    + SITE
                    + "\",\"email\":\"a@example.test\",\"downloadAttachments\":false}"));

    // then
    assertThat(jira.downloadAttachments()).isFalse();
  }

  @Test
  @DisplayName("cloudId가 없으면 사이트 origin으로 API를 부른다")
  void callsSiteWithoutCloudId() {
    // when
    JiraConfiguration jira =
        read(config("{\"siteUrl\":\"" + SITE + "/\",\"email\":\"a@example.test\"}"));

    // then
    assertThat(jira.apiBase()).isEqualTo(SITE);
  }

  @Test
  @DisplayName("cloudId가 있으면 Atlassian cloud gateway로 API를 부른다")
  void callsGatewayWithCloudId() {
    // when
    JiraConfiguration jira =
        read(
            config(
                "{\"siteUrl\":\""
                    + SITE
                    + "\",\"email\":\"a@example.test\",\"cloudId\":\""
                    + CLOUD_ID
                    + "\"}"));

    // then
    assertThat(jira.apiBase()).isEqualTo("https://api.atlassian.com/ex/jira/" + CLOUD_ID);
  }
}
