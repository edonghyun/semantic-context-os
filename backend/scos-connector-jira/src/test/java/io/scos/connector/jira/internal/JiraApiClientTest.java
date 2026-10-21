package io.scos.connector.jira.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.scos.connector.jira.internal.JiraClientScenario.FailureView;
import io.scos.support.FakeHttpServer.Response;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/**
 * Jira REST 클라이언트(TS JiraApiClient·classifyJiraError): Basic 인증(email:token), /rest/ 경로만, 2xx가 아니면
 * 상태와 Retry-After(초·HTTP-date)를 담은 실패, 응답 본문·토큰은 오류에 싣지 않는다. 분류 기대값은 TS가 만든
 * 표(expected-client.json)다.
 */
@DisplayName("Jira REST 클라이언트는 TS JiraApiClient·classifyJiraError와 같다")
class JiraApiClientTest {
  final JiraClientScenario jira = new JiraClientScenario();

  @AfterEach
  void close() {
    jira.close();
  }

  @Nested
  @DisplayName("필드 목록이 오류로 답하면")
  class ErrorAnswer {
    static Stream<Arguments> statusCases() {
      return StreamSupport.stream(JiraFixtures.client().get("statusCases").spliterator(), false)
          .map(
              c -> {
                Optional<String> retryAfter =
                    c.get("retryAfter").isNull()
                        ? Optional.empty()
                        : Optional.of(c.get("retryAfter").asString());
                return Arguments.of(
                    "HTTP "
                        + c.get("status").asInt()
                        + retryAfter.map(r -> " Retry-After " + r).orElse(""),
                    c.get("status").asInt(),
                    retryAfter,
                    FailureView.expected(c.get("failure")));
              });
    }

    static Stream<Arguments> invalidBodyCases() {
      return StreamSupport.stream(
              JiraFixtures.client().get("invalidBodyCases").spliterator(), false)
          .map(
              c ->
                  Arguments.of(
                      c.get("name").asString(),
                      c.get("status").asInt(),
                      c.get("body").asString(),
                      FailureView.expected(c.get("failure"))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("statusCases")
    @DisplayName("오류 응답은 TS classifyJiraError와 같은 실패로 분류된다")
    void classifiesLikeTs(
        String label, int status, Optional<String> retryAfter, FailureView expected) {
      // given
      jira.fieldsAnswer(status, retryAfter);

      // when
      FailureView failure = jira.fieldsFailure();

      // then
      assertThat(failure).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidBodyCases")
    @DisplayName("2xx인데 JSON 본문이 아니면(빈 본문 포함) HTTP 502 실패로 분류된다")
    void classifiesInvalidBodyAs502(String name, int status, String body, FailureView expected) {
      // given
      jira.fieldsAnswerRaw(new Response(status, body, Map.of()));

      // when
      FailureView failure = jira.fieldsFailure();

      // then
      assertThat(failure).isEqualTo(expected);
    }

    @Test
    @DisplayName("오류 메시지에 응답 본문과 토큰을 싣지 않는다")
    void failureCarriesNoBodyOrToken() {
      // given
      jira.fieldsAnswer(429, Optional.of("12"));

      // when
      RuntimeException thrown = jira.failureOf(JiraClientScenario.FIELDS_PAGE);

      // then
      assertThat(thrown.getMessage())
          .isEqualTo("Jira request failed (HTTP 429)")
          .doesNotContain(jira.token());
    }
  }

  @Nested
  @DisplayName("필드 목록이 리다이렉트로 답하면")
  class RedirectAnswer {
    @Test
    @DisplayName("따라가지 않고 전송 실패로 분류된다")
    void classifiesRedirectAsTransportFailure() {
      // given
      jira.fieldsAnswerRaw(new Response(302, "", Map.of("Location", "https://other.example/")));

      // when
      FailureView failure = jira.fieldsFailure();

      // then
      assertThat(failure)
          .isEqualTo(FailureView.expected(JiraFixtures.client().get("transportFailure")));
    }

    @Test
    @DisplayName("Location으로 요청을 보내지 않는다(Basic 인증이 새지 않는다)")
    void doesNotFollowRedirect() {
      // given
      jira.fieldsRedirectElsewhere();

      // when: 리다이렉트는 실패다(성공하면 given 준비 실패)
      jira.failureOf(JiraClientScenario.FIELDS_PAGE);

      // then
      assertThat(jira.requestsTo(JiraClientScenario.ELSEWHERE)).isZero();
    }
  }

  @Nested
  @DisplayName("응답 상태가 2xx·3xx 경계에 있으면")
  class StatusBounds {
    @ParameterizedTest(name = "HTTP {0}")
    @ValueSource(ints = {300, 399})
    @DisplayName("3xx 경계는 따라가지 않고 전송 실패로 분류된다")
    void classifiesRedirectBoundsAsTransportFailure(int status) {
      // given
      jira.fieldsAnswerRaw(new Response(status, "", Map.of()));

      // when
      FailureView failure = jira.fieldsFailure();

      // then
      assertThat(failure)
          .isEqualTo(FailureView.expected(JiraFixtures.client().get("transportFailure")));
    }

    @ParameterizedTest(name = "HTTP {0}")
    @ValueSource(ints = {200, 299})
    @DisplayName("2xx 경계는 성공이다")
    void acceptsSuccessBounds(int status) {
      // given
      jira.fieldsAnswerRaw(new Response(status, "[]", Map.of()));

      // when
      JsonNode fields = jira.readFields();

      // then
      assertThat(fields.isArray()).isTrue();
    }
  }

  @Nested
  @DisplayName("데이터셋 응답으로 조회하면")
  class Requests {
    @Test
    @DisplayName("데이터셋 email과 토큰의 Basic 헤더를 보낸다")
    void sendsBasicAuthorization() {
      // when
      jira.read(JiraClientScenario.DASHBOARDS_PAGE);

      // then
      assertThat(jira.requests().getFirst().header("Authorization"))
          .isEqualTo(jira.expectedAuthorization());
    }

    @Test
    @DisplayName("JSON 응답을 요청한다")
    void acceptsJson() {
      // when
      jira.read(JiraClientScenario.DASHBOARDS_PAGE);

      // then
      assertThat(jira.requests().getFirst().header("Accept")).isEqualTo("application/json");
    }

    @Test
    @DisplayName("file: 자격 증명 파일의 토큰으로 인증한다")
    void authenticatesWithFileCredential(@TempDir Path dir) throws Exception {
      // when
      jira.readDashboardsWithFileCredential(dir);

      // then
      assertThat(jira.requests().getFirst().header("Authorization"))
          .isEqualTo(jira.expectedAuthorization());
    }

    @Test
    @DisplayName("네 요청을 동시에 진행한다")
    void runsFourReadsConcurrently() throws Exception {
      // given
      jira.fieldsHeldUntilFourInFlight();

      // when: 네 요청이 모두 필드 배열을 받는다(아니면 given 준비 실패)
      jira.readFieldsFromFourThreads();

      // then
      assertThat(jira.maxFieldsInFlight()).isEqualTo(JiraClientScenario.CONCURRENT_READS);
    }
  }

  @Nested
  @DisplayName("/rest/ 밖이거나 역슬래시·줄바꿈이 든 경로를 부르면")
  class UnsafePath {
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/other/api", "/rest/api\\3", "/rest/api/3/field\r\nX-Injected: 1"})
    @DisplayName("IllegalArgumentException으로 거부되고, 요청을 보내지 않는다")
    void refusesUnsafePath(String path) {
      // when
      Throwable thrown = catchThrowable(() -> jira.read(path));

      // then
      assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
      assertThat(jira.requests()).isEmpty();
    }
  }

  @Nested
  @DisplayName("데이터셋 필드 목록을 부르면")
  class DatasetFields {
    @Test
    @DisplayName("첫 응답이 Retry-After 1초의 rate_limited다")
    void firstAnswerIsRateLimited() {
      // when
      FailureView failure = jira.fieldsFailure();

      // then
      assertThat(failure)
          .isEqualTo(
              new FailureView("rate_limited", true, "Jira request failed (HTTP 429)", 1000L));
    }

    @Test
    @DisplayName("다시 부르면 필드 둘을 준다")
    void secondAnswerListsFields() {
      // given
      jira.givenFieldsRateLimitedOnce();

      // when
      JsonNode fields = jira.readFields();

      // then
      assertThat(fields)
          .extracting(f -> f.get("id").asString())
          .containsExactly("summary", "customfield_10010");
    }
  }

  @Nested
  @DisplayName("첨부 원본을 받으면")
  class Attachments {
    @Test
    @DisplayName("redirect=false로 원본 바이트를 받는다")
    void downloadsOriginalBytes() {
      // when
      JiraApiClient.Attachment png = jira.attachment("50002");

      // then
      assertThat(png.bytes()).containsExactly(JiraFixtures.attachmentBytes("50002"));
    }

    @Test
    @DisplayName("응답 Content-Type을 미디어 유형으로 쓴다")
    void usesResponseContentType() {
      // when
      JiraApiClient.Attachment log = jira.attachment("50001");

      // then
      assertThat(log.mediaType()).isEqualTo("text/plain");
    }

    @Test
    @DisplayName("Content-Type이 없으면 application/octet-stream이다")
    void defaultsToOctetStream() {
      // given
      jira.attachmentAnswers("9", Response.binary(200, new byte[] {1, 2}, Map.of()));

      // when
      JiraApiClient.Attachment unknown = jira.attachment("9");

      // then
      assertThat(unknown.mediaType()).isEqualTo("application/octet-stream");
    }

    @Test
    @DisplayName("거부되면 그 상태의 실패다")
    void failsWithStatus() {
      // given
      jira.attachmentAnswers("9", new Response(403, "", Map.of()));

      // when
      Throwable thrown = catchThrowable(() -> jira.attachment("9"));

      // then
      assertThat(thrown)
          .isInstanceOf(JiraApiException.class)
          .hasMessage("Jira request failed (HTTP 403)");
    }
  }

  @Nested
  @DisplayName("경로 조각을 인코딩하면")
  class PathSegment {
    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(
        delimiter = '|',
        value = {
          "50001|50001",
          "a b|a%20b",
          "a/b?c|a%2Fb%3Fc",
          "!~*'()|!~*'()",
          "한글|%ED%95%9C%EA%B8%80"
        })
    @DisplayName("TS encodeURIComponent처럼 인코딩한다")
    void encodesPathSegmentLikeTs(String value, String encoded) {
      // when
      String segment = JiraApiClient.segment(value);

      // then
      assertThat(segment).isEqualTo(encoded);
    }
  }
}
