package io.scos.connector.slack.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.spi.access.AccessFailureReason;
import io.scos.spi.access.AccessKey;
import io.scos.spi.access.DocumentAcl;
import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.CallFailureException;
import io.scos.spi.connector.RawAcl;
import io.scos.support.LogCapture;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

// 제품 로거의 다른 테스트 이벤트가 캡처에 섞이지 않게 한다.
@Isolated
@DisplayName("Slack의 확정 채널 거부는 해당 채널만 닫는다(T-fix5)")
class SlackUnreadableChannelTest {
  private final SlackAccessScenario s = new SlackAccessScenario();
  private final LogCapture logs = LogCapture.of(SlackAccessCollection.class);

  @AfterEach
  void close() {
    logs.close();
    s.close();
  }

  @ParameterizedTest(name = "{0}, recorded={1}")
  @CsvSource({"info,false", "info,true", "members,false", "members,true"})
  @DisplayName("채널 하나가 없으면 빈 그룹으로 닫고 다른 채널은 정상 완료한다")
  void unreadableChannelDoesNotDiscardDirectory(String method, boolean recorded) {
    // given
    s.secondChannel();
    s.unreadableChannel(method, recorded);
    // when
    var result = s.collect(SlackAccessScenario.MESSAGE);
    // then
    assertThat(result.failure()).isEmpty();
    assertThat(result.group("C1").members()).isEmpty();
    assertThat(result.group("C2").members()).containsExactly("U1");
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"info", "members"})
  @DisplayName("읽을 수 없는 채널의 원본 ACL은 acl_unreadable 빈 키로 닫힌다")
  void unreadableChannelClosesRawAcl(String method) {
    // given
    s.unreadableChannel(method, false);
    // when
    var result = s.collect(SlackAccessScenario.MESSAGE);
    // then
    assertThat(result.acls().get(SlackAccessScenario.MESSAGE))
        .isEqualTo(RawAcl.failed(AccessFailureReason.ACL_UNREADABLE, SlackAccessScenario.NOW));
    assertThat(result.unreadableChannels()).containsExactly("C1");
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"info", "members"})
  @DisplayName("페이지 사이 시각이 전진해도 거부 채널 carrier는 한 번도 OK가 되지 않는다")
  void unreadableCarrierNeverOpensBeforeVerdict(String method) {
    // given
    s.advanceClockBetweenPages();
    s.unreadableChannel(method, false);
    // when
    var result = s.collect(SlackAccessScenario.MESSAGE);
    // then
    assertThat(result.failure()).isEmpty();
    assertThat(result.observationsOf(SlackAccessScenario.MESSAGE))
        .containsExactly(
            RawAcl.failed(AccessFailureReason.ACL_UNREADABLE, SlackAccessScenario.NOW));
  }

  @Test
  @DisplayName("닫힌 채널과 접두사가 겹쳐도 다른 carrier의 키는 보존된다")
  void unreadableChannelDoesNotCloseOtherCarriers() {
    // given
    s.unreadableChannel("info", false);
    // when
    var result = s.collect(SlackAccessScenario.MESSAGE, "C10:1700000000.000001");
    // then
    assertThat(result.acls().get("C10:1700000000.000001"))
        .isEqualTo(
            RawAcl.observed(
                DocumentAcl.Status.OK,
                Set.of(AccessKey.parse("group:slack-synthetic:C10")),
                SlackAccessScenario.NOW));
  }

  @Test
  @DisplayName("채널 닫기 저장이 실패하면 신원 순회를 시작하지 않고 즉시 전파한다")
  void unreadableChannelDoesNotSwallowSinkFailure() {
    // given
    s.unreadableChannel("members", false);
    s.failSinkOnGroup("C1");
    // when
    var result = s.collect();
    // then
    assertThat(result.failure())
        .hasValueSatisfying(
            failure ->
                assertThat(failure)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("injected ACL sink failure"));
    assertThat(s.requests()).noneMatch(request -> request.startsWith("/api/users.list"));
  }

  @Test
  @DisplayName("채널 거부 경고는 채널 ID와 고정 원인을 남긴다")
  void unreadableWarningIdentifiesChannel() {
    // given
    s.unreadableChannel("info", false);
    // when
    s.collect();
    // then
    assertThat(logs.events("collection.slack_access.channel_unreadable"))
        .singleElement()
        .satisfies(
            event -> {
              assertThat(
                      List.of(
                          event.path("level").asString(),
                          event.path("sourceInstanceId").asString()))
                  .containsExactly("warn", "slack-synthetic");
              assertThat(event.path("detail").toString())
                  .isEqualTo(
                      "{\"reason\":\"acl_unreadable\",\"operation\":\"slack.conversations.info\",\"providerLocator\":{\"channelId\":\"C1\"}}");
            });
  }

  @ParameterizedTest(name = "HTTP {0}")
  @ValueSource(ints = {403, 404, 500})
  @DisplayName("HTTP 상태와 본문 문자열만으로 확정 채널 거부로 넓히지 않는다")
  void transportFailureRemainsIncomplete(int status) {
    // given
    s.failAttempts(
        "/api/conversations.info", FakeSlackServer.Fault.http(status, "channel_not_found"));
    // when
    var result = s.collect();
    // then
    assertThat(result.failure()).isPresent();
    assertThat(result.unreadableChannels()).isEmpty();
  }

  @ParameterizedTest(name = "코드 {0}")
  @ValueSource(
      strings = {"missing_scope", "not_in_channel", "invalid_auth", "channel_not_found_suffix"})
  @DisplayName("다른 Slack 오류 코드는 채널 거부 확정으로 처리하지 않는다")
  void otherApiFailuresRemainIncomplete(String code) {
    // given
    s.failAttempts("/api/conversations.info", FakeSlackServer.Fault.apiError(code));
    // when
    var result = s.collect();
    // then
    assertThat(result.failure()).isPresent();
    assertThat(result.unreadableChannels()).isEmpty();
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {"auth.test", "users.conversations", "conversations.info", "conversations.members"})
  @DisplayName("확정 거부가 아닌 장애는 기존 로컬 carrier 키와 디렉터리 미완료를 유지한다")
  void incompleteWalkRetainsLocalCarrierAcl(String method) {
    // given
    s.failAttempts("/api/" + method, FakeSlackServer.Fault.apiError("missing_scope"));
    // when
    var result = s.collect(SlackAccessScenario.MESSAGE);
    // then
    assertThat(result.failure()).isPresent();
    assertThat(result.observationsOf(SlackAccessScenario.MESSAGE))
        .containsExactly(
            RawAcl.observed(
                DocumentAcl.Status.OK,
                Set.of(AccessKey.parse("group:slack-synthetic:C1")),
                SlackAccessScenario.NOW));
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"users.conversations", "conversations.members"})
  @DisplayName("API 재시도 중 취소되면 미판정 carrier를 내보내지 않는다")
  void cancelledWalkDoesNotEmitPendingCarriers(String method) {
    // given
    s.cancelOnRetry();
    s.failNext("/api/" + method, FakeSlackServer.Fault.rateLimited(2));
    // when
    var result = s.collect(SlackAccessScenario.MESSAGE);
    // then
    assertThat(result.failure())
        .get()
        .isInstanceOfSatisfying(
            CallFailureException.class,
            error -> assertThat(error.failure().kind()).isEqualTo(CallFailure.Kind.CANCELLED));
    assertThat(result.observationsOf(SlackAccessScenario.MESSAGE)).isEmpty();
  }

  @Test
  @DisplayName("채널 요청 제한이 소진되면 디렉터리는 미완료다")
  void exhaustedRateLimitRemainsIncomplete() {
    // given
    s.failAttempts("/api/conversations.members", FakeSlackServer.Fault.rateLimited(2));
    // when
    var result = s.collect();
    // then
    assertThat(result.failure()).isPresent();
    assertThat(result.unreadableChannels()).isEmpty();
  }

  @Test
  @DisplayName("신원 조회의 channel_not_found는 채널 거부 확정으로 처리하지 않는다")
  void nonChannelOperationRemainsIncomplete() {
    // given
    s.failAttempts("/api/users.list", FakeSlackServer.Fault.apiError("channel_not_found"));
    // when
    var result = s.collect();
    // then
    assertThat(result.failure())
        .get()
        .isInstanceOfSatisfying(
            CallFailureException.class,
            error ->
                assertThat(error.failure().kind()).isEqualTo(CallFailure.Kind.PERMISSION_DENIED));
    assertThat(result.unreadableChannels()).isEmpty();
  }
}
