package io.scos.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.support.LogCapture;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("사용자 Bearer 필터는 활성 사용자의 토큰만 통과시킨다")
// 공용 클래스 로거를 캡처하므로 다른 명세의 장애 이벤트와 섞지 않는다(TESTING §6.3).
@Isolated
class PrincipalBearerFilterTest {
  private final PrincipalBearerScenario scenario = new PrincipalBearerScenario();

  @AfterEach
  void close() {
    scenario.close();
  }

  @Test
  @DisplayName("유효한 토큰은 principal을 요청 속성에 넣고 통과한다")
  void validTokenPassesPrincipal() throws Exception {
    var reply = scenario.validRequest();

    // then
    assertThat(reply.principal()).contains(PrincipalBearerScenario.ALICE);
  }

  @Test
  @DisplayName("헤더 없는 요청은 고정 JSON과 Bearer 요구로 거부한다")
  void absentHeaderIsUnauthorized() throws Exception {
    var reply = scenario.request(null);

    // then
    assertThat(reply.response().getStatus()).isEqualTo(401);
    assertThat(reply.response().getHeader("WWW-Authenticate")).isEqualTo("Bearer");
    assertThat(reply.response().getContentAsByteArray())
        .isEqualTo("{\"error\":\"unauthorized\"}".getBytes(StandardCharsets.UTF_8));
  }

  @Test
  @DisplayName("틀린 토큰의 응답은 헤더 없는 요청과 같다")
  void wrongTokenMatchesAbsent() throws Exception {
    var baseline = scenario.request(null).wire();

    var reply = scenario.request("Bearer unknown-token-fixture");

    // then
    assertThat(reply.wire()).usingRecursiveComparison().isEqualTo(baseline);
  }

  @Test
  @DisplayName("폐기한 토큰의 응답은 헤더 없는 요청과 같다")
  void revokedTokenMatchesAbsent() throws Exception {
    var baseline = scenario.request(null).wire();
    scenario.revoke();

    var reply = scenario.validRequest();

    // then
    assertThat(reply.wire()).usingRecursiveComparison().isEqualTo(baseline);
  }

  @Test
  @DisplayName("비활성 사용자의 응답은 헤더 없는 요청과 같다")
  void inactiveUserMatchesAbsent() throws Exception {
    var baseline = scenario.request(null).wire();
    scenario.deactivate();

    var reply = scenario.validRequest();

    // then
    assertThat(reply.wire()).usingRecursiveComparison().isEqualTo(baseline);
  }

  @Test
  @DisplayName("Bearer 아닌 스킴의 응답은 헤더 없는 요청과 같다")
  void otherSchemeMatchesAbsent() throws Exception {
    var baseline = scenario.request(null).wire();

    var reply = scenario.request("Basic " + PrincipalBearerScenario.TOKEN);

    // then
    assertThat(reply.wire()).usingRecursiveComparison().isEqualTo(baseline);
  }

  @Test
  @DisplayName("소문자 bearer 스킴의 응답은 헤더 없는 요청과 같다")
  void lowercaseSchemeMatchesAbsent() throws Exception {
    var baseline = scenario.request(null).wire();

    var reply = scenario.request("bearer " + PrincipalBearerScenario.TOKEN);

    // then
    assertThat(reply.wire()).usingRecursiveComparison().isEqualTo(baseline);
  }

  @Test
  @DisplayName("빈 Bearer 토큰의 응답은 헤더 없는 요청과 같다")
  void emptyTokenMatchesAbsent() throws Exception {
    var baseline = scenario.request(null).wire();

    var reply = scenario.request("Bearer ");

    // then
    assertThat(reply.wire()).usingRecursiveComparison().isEqualTo(baseline);
  }

  @Test
  @DisplayName("공백 없는 Bearer 접두사의 응답은 헤더 없는 요청과 같다")
  void missingSpaceMatchesAbsent() throws Exception {
    var baseline = scenario.request(null).wire();

    var reply = scenario.request("Bearer");

    // then
    assertThat(reply.wire()).usingRecursiveComparison().isEqualTo(baseline);
  }

  @Test
  @DisplayName("401 응답의 콘텐츠 유형은 JSON이다")
  void unauthorizedIsJson() throws Exception {
    var reply = scenario.request(null);

    // then
    assertThat(reply.response().getContentType()).isEqualTo("application/json");
  }

  @Test
  @DisplayName("401은 다음 단계에 도달하지 않는다")
  void unauthorizedStopsChain() throws Exception {
    var reply = scenario.request(null);

    // then
    assertThat(reply.reachedChain()).isFalse();
  }

  @Test
  @DisplayName("저장소 오류는 인증 실패로 숨기지 않고 500으로 응답한다")
  void storageFailureIsInternal() throws Exception {
    scenario.failStorage();

    var reply = scenario.validRequest();

    // then
    assertThat(reply.response().getStatus()).isEqualTo(500);
    assertThat(reply.response().getHeader("WWW-Authenticate")).isNull();
  }

  @Test
  @DisplayName("500 본문은 저장소 문구 대신 고정 오류와 참조 ID만 담는다")
  void internalBodyMasksStorageFailure() throws Exception {
    scenario.failStorage();

    var reply = scenario.validRequest();

    // then
    assertThat(reply.response().getContentType()).isEqualTo("application/json");
    assertThat(reply.response().getContentAsString())
        .matches("\\{\"error\":\"internal\",\"ref\":\"[0-9a-f]{8}-[0-9a-f]{3}\"}");
    assertThat(reply.principal()).isEmpty();
  }

  @Test
  @DisplayName("인증 저장소 장애는 응답 참조 ID와 원인을 이벤트에 남긴다")
  void storageFailureLogsReference() throws Exception {
    scenario.failStorage();
    try (var logs = LogCapture.of(PrincipalBearerFilter.class)) {
      var reply = scenario.validRequest();

      // then
      var ref =
          JsonMapper.builder().build().readTree(reply.response().getContentAsString()).path("ref");
      assertThat(logs.events("http.authentication.failed"))
          .singleElement()
          .satisfies(
              event -> {
                assertThat(event.path("detail").path("ref")).isEqualTo(ref);
                assertThat(event.path("error").path("message").asString())
                    .isEqualTo("storage-detail-fixture");
              });
    }
  }

  @Test
  @DisplayName("인증한 토큰은 사용 시각을 다음 배치에 남긴다")
  void authenticatedUseIsRecorded() throws Exception {
    scenario.validRequest();

    // then
    assertThat(scenario.persistedUsage()).contains(PrincipalBearerScenario.NOW);
  }

  @Test
  @DisplayName("저장소 Error는 원문을 감춘 메시지로 다시 던진다")
  void fatalStorageFailureIsMaskedAndRethrown() {
    scenario.failStorageFatally();

    // when·then
    assertThatThrownBy(scenario::validRequest)
        .isInstanceOf(Error.class)
        .hasMessageMatching("internal \\(ref [0-9a-f]{8}-[0-9a-f]{3}\\)");
  }

  @Test
  @DisplayName("저장소 Error는 재던지기 전에 고정 500 응답을 커밋한다")
  void fatalStorageFailureCommitsFixedResponse() throws Exception {
    // given
    scenario.failStorageFatally();

    // when
    var reply = scenario.fatalRequest();

    // then
    assertThat(reply.committed()).isTrue();
    assertThat(reply.status()).isEqualTo(500);
    assertThat(reply.body())
        .matches("\\{\"error\":\"internal\",\"ref\":\"[0-9a-f]{8}-[0-9a-f]{3}\"}");
  }

  @Test
  @DisplayName("저장소 Error는 cause 없이 원본을 suppressed로 보존한다")
  void fatalStorageFailurePreservesOriginal() throws Exception {
    // given
    Error original = scenario.failStorageFatally();

    // when
    var reply = scenario.fatalRequest();

    // then
    assertThat(reply.failure())
        .hasValueSatisfying(
            failure -> {
              assertThat(failure).hasNoCause();
              assertThat(failure.getSuppressed()).containsExactly(original);
            });
  }

  @Test
  @DisplayName("인증 장애 로그에는 제시한 토큰 원문을 싣지 않는다")
  void authenticationLogExcludesToken() throws Exception {
    scenario.failStorage();
    try (var logs = LogCapture.of(PrincipalBearerFilter.class)) {
      scenario.validRequest();

      // then
      assertThat(logs.events().toString())
          .contains("http.authentication.failed")
          .doesNotContain(PrincipalBearerScenario.TOKEN);
    }
  }

  @Test
  @DisplayName("폐기한 토큰은 사용 시각을 기록하지 않는다")
  void rejectedUseIsNotRecorded() throws Exception {
    scenario.revoke();

    scenario.validRequest();

    // then
    assertThat(scenario.persistedUsage()).isEmpty();
  }
}
