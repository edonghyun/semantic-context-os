package io.scos.connector.googledrive.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.connector.googledrive.internal.GoogleSessionScenario.Event;
import io.scos.connector.googledrive.internal.GoogleSessionScenario.FailureView;
import io.scos.connector.googledrive.internal.GoogleSessionScenario.Result;
import io.scos.support.FakeHttpServer;
import io.scos.support.FakeHttpServer.Response;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/**
 * Google 세션(TS GoogleDriveSession·classifyGoogleError): refresh token으로 access token을 받아 메모리에 두고 만료
 * 60초 전에 갱신한다. 401은 토큰을 무효화한다. 2xx가 아니면 상태·403 본문의 rate limit 이유·Retry-After를 담은 실패다. 기대값은 TS가 만든
 * 표(expected-client.json)다.
 */
@DisplayName("Google 세션은 TS GoogleDriveSession·classifyGoogleError와 같다")
class GoogleDriveSessionTest {
  /** 비밀값이 아닌 것처럼 보이지만 토큰 끝점이 돌려준 오류 본문 — 오류에 실리면 안 된다. */
  static final String PRIVATE_TOKEN_ERROR = "private token error";

  /** 1 MiB를 넘는 403 rate limit 본문 — 읽지 않고 권한 없음으로 본다. */
  static final String OVERSIZED_RATE_LIMIT_BODY =
      "{\"error\":{\"errors\":[{\"reason\":\"rateLimitExceeded\"}]},\"pad\":\""
          + "x".repeat(1024 * 1024)
          + "\"}";

  final GoogleSessionScenario google = new GoogleSessionScenario();

  @AfterEach
  void close() {
    google.close();
  }

  static Stream<Arguments> lifecycleCases() {
    return cases("lifecycleCases").map(c -> Arguments.of(c.get("label").asString(), c));
  }

  static Stream<JsonNode> cases(String name) {
    return StreamSupport.stream(GoogleDriveFixtures.client().get(name).spliterator(), false);
  }

  /** TS 수명 사례 하나를 돌린 결과. */
  record Lifecycle(List<Event> events, List<Result> results) {}

  Lifecycle runLifecycle(JsonNode c) {
    google.tokenAnswers(GoogleSessionScenario.responses(c.get("tokenReplies")));
    GoogleDriveSession session = google.session();
    List<Result> results = new ArrayList<>();
    for (JsonNode call : c.get("calls")) {
      Response reply =
          call.has("reply")
              ? GoogleDriveFixtures.response(call.get("reply"))
              : FakeHttpServer.ok("{\"ok\":true}");
      results.add(google.readAboutAt(session, call.get("at").asLong(), reply));
    }
    return new Lifecycle(google.events(), results);
  }

  @Nested
  @DisplayName("TS 수명 사례대로 토큰 끝점과 데이터가 답하면")
  class TokenLifecycle {
    @ParameterizedTest(name = "{0}")
    @MethodSource("io.scos.connector.googledrive.internal.GoogleDriveSessionTest#lifecycleCases")
    @DisplayName("TS와 같은 시각에 토큰을 갱신하고 같은 Bearer로 부른다")
    void refreshesLikeTs(String label, JsonNode c) {
      // when
      Lifecycle java = runLifecycle(c);

      // then
      assertThat(java.events())
          .containsExactlyElementsOf(
              StreamSupport.stream(c.get("events").spliterator(), false)
                  .map(Event::expected)
                  .toList());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("io.scos.connector.googledrive.internal.GoogleDriveSessionTest#lifecycleCases")
    @DisplayName("호출마다 TS와 같은 결과를 낸다")
    void answersLikeTs(String label, JsonNode c) {
      // when
      Lifecycle java = runLifecycle(c);

      // then
      assertThat(java.results())
          .containsExactlyElementsOf(
              StreamSupport.stream(c.get("results").spliterator(), false)
                  .map(Result::expected)
                  .toList());
    }
  }

  static Stream<Arguments> tokenErrorCases() {
    return cases("tokenErrorCases")
        .map(
            c ->
                Arguments.of(
                    c.get("label").asString(),
                    GoogleDriveFixtures.response(c.get("reply")),
                    Result.expected(c.get("result"))));
  }

  static Stream<Arguments> statusCases() {
    return cases("statusCases")
        .map(
            c -> {
              JsonNode reply = c.get("reply");
              Response response =
                  reply.has("oversizedRateLimitBody")
                      ? new Response(403, OVERSIZED_RATE_LIMIT_BODY, Map.of())
                      : GoogleDriveFixtures.response(reply);
              return Arguments.of(
                  c.get("label").asString(), response, Result.expected(c.get("result")));
            });
  }

  static Stream<Arguments> bodyCases() {
    return cases("bodyCases")
        .map(
            c ->
                Arguments.of(
                    c.get("label").asString(),
                    GoogleDriveFixtures.response(c.get("reply")),
                    Result.expected(c.get("result"))));
  }

  @Nested
  @DisplayName("TS 표의 응답으로 답하면")
  class TsTable {
    @ParameterizedTest(name = "{0}")
    @MethodSource("io.scos.connector.googledrive.internal.GoogleDriveSessionTest#tokenErrorCases")
    @DisplayName("토큰 끝점 응답은 TS와 같은 결과로 분류된다")
    void classifiesTokenEndpointLikeTs(String label, Response reply, Result expected) {
      // given
      google.tokenAnswers(List.of(reply));

      // when
      Result result = google.readAboutAt(google.session(), 0, FakeHttpServer.ok("{}"));

      // then
      assertThat(result).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("io.scos.connector.googledrive.internal.GoogleDriveSessionTest#statusCases")
    @DisplayName("오류 응답은 TS classifyGoogleError와 같은 실패로 분류된다")
    void classifiesLikeTs(String label, Response reply, Result expected) {
      // when
      Result result = google.readAboutAt(google.session(), 0, reply);

      // then
      assertThat(result).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("io.scos.connector.googledrive.internal.GoogleDriveSessionTest#bodyCases")
    @DisplayName("2xx 본문이 JSON 객체가 아니면 TS와 같은 502 실패다")
    void classifiesBodiesLikeTs(String label, Response reply, Result expected) {
      // when
      Result result = google.readAboutAt(google.session(), 0, reply);

      // then
      assertThat(result).isEqualTo(expected);
    }
  }

  static Stream<Arguments> pathCases() {
    return cases("pathCases")
        .map(
            c ->
                Arguments.of(
                    c.get("service").asString() + " " + c.get("path").asString(),
                    GoogleService.valueOf(c.get("service").asString().toUpperCase(Locale.ROOT)),
                    c.get("path").asString(),
                    c));
  }

  @Nested
  @DisplayName("서비스 경로를 부르면")
  class Paths {
    @ParameterizedTest(name = "{0}")
    @MethodSource("io.scos.connector.googledrive.internal.GoogleDriveSessionTest#pathCases")
    @DisplayName("TS와 같은 결과로 분류된다")
    void classifiesLikeTs(String label, GoogleService service, String path, JsonNode c) {
      // when
      Result result = google.read(google.session(), service, path);

      // then
      assertThat(result).isEqualTo(Result.expected(c.get("result")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("io.scos.connector.googledrive.internal.GoogleDriveSessionTest#pathCases")
    @DisplayName("서비스 접두가 아니거나 제어 문자가 든 경로는 토큰도 요청하지 않는다")
    void requestsOnlyValidPaths(String label, GoogleService service, String path, JsonNode c) {
      // when
      google.read(google.session(), service, path);

      // then
      assertThat(google.requestedPaths())
          .containsExactlyElementsOf(
              StreamSupport.stream(c.get("requested").spliterator(), false)
                  .map(u -> URI.create(u.asString()).getPath())
                  .toList());
    }
  }

  @Nested
  @DisplayName("access token을 갱신하면")
  class TokenRequest {
    final JsonNode ts = GoogleDriveFixtures.client().get("tokenRequest");

    @Test
    @DisplayName("TS URLSearchParams와 같은 폼 본문이다")
    void sendsSameFormBody() {
      // given
      String credential =
          "{\"client_id\":\"c id+/\",\"client_secret\":\"s&e=c~\",\"refresh_token\":\"1//r t\"}";

      // when
      google.read(google.session(credential), GoogleService.DRIVE, GoogleSessionScenario.ABOUT);

      // then
      assertThat(google.tokenRequests().getFirst().body()).isEqualTo(ts.get("body").asString());
    }

    @Test
    @DisplayName("폼 Content-Type의 POST다")
    void postsForm() {
      // when
      google.read(google.session(), GoogleService.DRIVE, GoogleSessionScenario.ABOUT);

      // then
      assertThat(google.tokenRequests().getFirst())
          .extracting(FakeHttpServer.Recorded::method, r -> r.header("Content-Type"))
          .containsExactly("POST", ts.get("headers").get("Content-Type").asString());
    }
  }

  @Nested
  @DisplayName("자격 증명이 오류나 문자열 표현에 드러날 때")
  class Secrets {
    @Test
    @DisplayName("토큰 끝점이 거절해도 오류에 자격 증명과 응답 본문을 싣지 않는다")
    void tokenFailureCarriesNoSecret() {
      // given
      google.tokenAnswers(List.of(new Response(400, PRIVATE_TOKEN_ERROR, Map.of())));
      GoogleDriveSession session = google.session();

      // when
      RuntimeException thrown =
          google.failureOf(
              () ->
                  session.json(
                      GoogleService.DRIVE,
                      GoogleSessionScenario.ABOUT,
                      GoogleSessionScenario.TIMEOUT));

      // then
      assertThat(thrown.toString())
          .doesNotContain(PRIVATE_TOKEN_ERROR)
          .doesNotContain(secret("client_secret"))
          .doesNotContain(secret("refresh_token"));
    }

    @Test
    @DisplayName("자격 증명 JSON이 깨져도 오류에 그 내용을 싣지 않는다")
    void credentialFailureCarriesNoContent() {
      // given
      String broken = "{\"client_id\":\"c\",\"client_secret\":\"leaked-secret\",";

      // when
      RuntimeException thrown = google.failureOf(() -> google.session(broken));

      // then
      assertThat(thrown).hasMessage("Invalid Google OAuth credential JSON");
    }

    @Test
    @DisplayName("자격 증명의 문자열 표현은 client id만 보이고 비밀값을 가린다")
    void credentialStringHidesSecrets() {
      // when
      GoogleCredential credential =
          GoogleCredential.parse(GoogleDriveFixtures.JSON, GoogleDriveFixtures.credentialJson());

      // then
      assertThat(credential.toString())
          .isEqualTo(
              "GoogleCredential[clientId="
                  + secret("client_id")
                  + ", clientSecret=***, refreshToken=***]");
    }

    @Test
    @DisplayName("데이터 요청이 실패해도 오류에 access token을 싣지 않는다")
    void requestFailureCarriesNoAccessToken() {
      // given
      google.dataAnswers(new Response(403, "{}", Map.of()));
      GoogleDriveSession session = google.session();

      // when
      RuntimeException thrown =
          google.failureOf(
              () ->
                  session.json(
                      GoogleService.DRIVE,
                      GoogleSessionScenario.ABOUT,
                      GoogleSessionScenario.TIMEOUT));

      // then
      assertThat(thrown.toString()).doesNotContain("access-1");
    }

    private String secret(String key) {
      return GoogleDriveFixtures.dataset().get("credential").get(key).asString();
    }
  }

  @Nested
  @DisplayName("alt=media 원본을 내려받으면")
  class Download {
    @Test
    @DisplayName("alt=media 바이트를 그대로 돌려준다")
    void returnsBytes() {
      // given
      google.dataAnswers(Response.binary(200, PDF, Map.of("Content-Type", "application/pdf")));

      // when
      byte[] bytes = google.download(google.session(), PDF.length);

      // then
      assertThat(bytes).isEqualTo(PDF);
    }

    @Test
    @DisplayName("공유 드라이브 파일도 받도록 요청한다")
    void asksForMediaAcrossDrives() {
      // given
      google.dataAnswers(Response.binary(200, PDF, Map.of()));

      // when
      google.download(google.session(), PDF.length);

      // then
      assertThat(google.downloadParams())
          .containsExactlyInAnyOrderEntriesOf(Map.of("alt", "media", "supportsAllDrives", "true"));
    }

    @Test
    @DisplayName("상한을 넘는 본문은 GoogleContentTooLarge다")
    void rejectsOversizedBody() {
      // given
      google.dataAnswers(Response.binary(200, PDF, Map.of()));
      GoogleDriveSession session = google.session();

      // when
      RuntimeException thrown = google.failureOf(() -> google.download(session, PDF.length - 1));

      // then
      assertThat(thrown).isInstanceOf(GoogleContentTooLargeException.class);
    }

    @Test
    @DisplayName("오류 응답은 JSON 요청과 같은 실패다")
    void classifiesErrorsLikeJson() {
      // given
      google.dataAnswers(new Response(404, "", Map.of()));
      GoogleDriveSession session = google.session();

      // when
      RuntimeException thrown = google.failureOf(() -> google.download(session, PDF.length));

      // then
      assertThat(thrown).hasMessage("Google API request failed (HTTP 404)");
    }
  }

  /** 합성 PDF 원본. */
  static final byte[] PDF = "%PDF-1.4 synthetic\n".getBytes(StandardCharsets.UTF_8);

  @Nested
  @DisplayName("응답 상태가 2xx·3xx 경계에 있으면")
  class StatusBounds {
    @ParameterizedTest(name = "HTTP {0}")
    @ValueSource(ints = {200, 299})
    @DisplayName("2xx 경계는 성공이다")
    void acceptsSuccessBounds(int status) {
      // when
      Result result = google.readAboutAt(google.session(), 0, new Response(status, "{}", Map.of()));

      // then
      assertThat(result.ok()).isTrue();
    }

    @ParameterizedTest(name = "HTTP {0}")
    @ValueSource(ints = {300, 399})
    @DisplayName("3xx 경계는 따라가지 않고 전송 실패다")
    void refusesRedirectBounds(int status) {
      // when
      Result result = google.readAboutAt(google.session(), 0, new Response(status, "", Map.of()));

      // then
      assertThat(result.failure())
          .isEqualTo(FailureView.expected(GoogleDriveFixtures.client().get("transportFailure")));
    }

    @ParameterizedTest(name = "HTTP {0}")
    @ValueSource(ints = {200, 299})
    @DisplayName("토큰 끝점의 2xx 경계는 성공이다")
    void acceptsTokenSuccessBounds(int status) {
      // given
      google.tokenAnswers(List.of(new Response(status, ACCESS_TOKEN_REPLY, Map.of())));

      // when
      Result result = google.readAboutAt(google.session(), 0, FakeHttpServer.ok("{}"));

      // then
      assertThat(result.ok()).isTrue();
    }

    @ParameterizedTest(name = "HTTP {0}")
    @ValueSource(ints = {300, 399})
    @DisplayName("토큰 끝점의 3xx 경계는 따라가지 않고 전송 실패다")
    void refusesTokenRedirectBounds(int status) {
      // given
      google.tokenAnswers(List.of(new Response(status, "", Map.of())));

      // when
      Result result = google.readAboutAt(google.session(), 0, FakeHttpServer.ok("{}"));

      // then
      assertThat(result.failure())
          .isEqualTo(FailureView.expected(GoogleDriveFixtures.client().get("transportFailure")));
    }
  }

  @Nested
  @DisplayName("본문이 정확히 1 MiB이면")
  class SmallBodies {
    @Test
    @DisplayName("정확히 1 MiB인 403 본문의 rate limit 이유는 읽는다")
    void readsRateLimitBodyAtLimit() {
      // when
      Result result =
          google.readAboutAt(
              google.session(), 0, new Response(403, padded(RATE_LIMIT_BODY), Map.of()));

      // then
      assertThat(result.failure().kind()).isEqualTo("rate_limited");
    }

    @Test
    @DisplayName("정확히 1 MiB인 토큰 응답은 읽는다")
    void readsTokenBodyAtLimit() {
      // given
      google.tokenAnswers(List.of(new Response(200, padded(ACCESS_TOKEN_REPLY), Map.of())));

      // when
      Result result = google.readAboutAt(google.session(), 0, FakeHttpServer.ok("{}"));

      // then
      assertThat(result.ok()).isTrue();
    }

    /** JSON 객체 끝에 공백을 채워 UTF-8로 정확히 1 MiB로 만든다. */
    static String padded(String json) {
      return json + " ".repeat(ONE_MIB - json.getBytes(StandardCharsets.UTF_8).length);
    }
  }

  static final int ONE_MIB = 1024 * 1024;
  static final String ACCESS_TOKEN_REPLY = "{\"access_token\":\"access-1\",\"expires_in\":3600}";
  static final String RATE_LIMIT_BODY =
      "{\"error\":{\"errors\":[{\"reason\":\"rateLimitExceeded\"}]}}";

  @Nested
  @DisplayName("다른 호스트로 리다이렉트하면")
  class Redirects {
    @Test
    @DisplayName("데이터 요청은 그 호스트로 Bearer를 보내지 않는다")
    void dataRedirectIsNotFollowed() {
      // when
      google.readAboutAt(google.session(), 0, google.redirect(302));

      // then: 리다이렉트가 실제로 일어났다(아니면 given 준비 실패)
      google.requireRedirected(GoogleSessionScenario.ABOUT);
      assertThat(google.otherHostRequests()).isZero();
    }

    @Test
    @DisplayName("데이터 요청은 전송 실패로 분류된다")
    void dataRedirectIsTransportFailure() {
      // when
      Result result = google.readAboutAt(google.session(), 0, google.redirect(302));

      // then
      assertThat(result.failure())
          .isEqualTo(FailureView.expected(GoogleDriveFixtures.client().get("transportFailure")));
    }

    @Test
    @DisplayName("토큰 갱신은 그 호스트로 refresh token 폼을 보내지 않는다")
    void tokenRedirectIsNotFollowed() {
      // given
      google.tokenAnswers(List.of(google.redirect(307)));

      // when
      google.readAboutAt(google.session(), 0, FakeHttpServer.ok("{}"));

      // then: 리다이렉트가 실제로 일어났다(아니면 given 준비 실패)
      google.requireRedirected(GoogleSessionScenario.TOKEN);
      assertThat(google.otherHostRequests()).isZero();
    }
  }

  @Nested
  @DisplayName("토큰 끝점이 헤더로 보낼 수 없는 access token을 주면")
  class UnsendableAccessToken {
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"a\\r\\nX-Injected: 1", "a\\nb", "a\\u0000b", "a\\u0100b"})
    @DisplayName("헤더 지점에서 설정 오류로 끝난다")
    void rejectsUnsendableAccessToken(String token) {
      // given
      google.tokenAnswers(
          List.of(FakeHttpServer.ok("{\"access_token\":\"" + token + "\",\"expires_in\":3600}")));

      // when
      Result result = google.readAboutAt(google.session(), 0, FakeHttpServer.ok("{}"));

      // then
      assertThat(result.failure().kind()).isEqualTo("invalid_configuration");
    }

    @Test
    @DisplayName("데이터 요청을 보내지 않는다")
    void sendsNoDataWithUnsendableAccessToken() {
      // given
      google.tokenAnswers(
          List.of(FakeHttpServer.ok("{\"access_token\":\"a\\r\\nb\",\"expires_in\":3600}")));

      // when
      google.readAboutAt(google.session(), 0, FakeHttpServer.ok("{}"));

      // then
      assertThat(google.requestedPaths()).containsExactly(GoogleSessionScenario.TOKEN);
    }
  }
}
