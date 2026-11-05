package io.scos.connector.slack.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("가짜 Slack 접근 API는 공급자 페이지와 채널 정보를 서빙한다(C7)")
class FakeSlackAccessServerTest {
  private final SlackAccessScenario s = new SlackAccessScenario();

  @AfterEach
  void close() {
    s.close();
  }

  @Test
  @DisplayName("인증 응답은 fixture의 워크스페이스 id다")
  void authReturnsWorkspace() throws Exception {
    var body = s.response("/api/auth.test");
    // then
    assertThat(body.path("team_id").asString()).isEqualTo("T1");
  }

  @Test
  @DisplayName("채널 정보는 공개·비공개 상태를 서빙한다")
  void channelInfoReturnsVisibility() throws Exception {
    s.channelFlag("is_private", true);
    var body = s.response("/api/conversations.info?channel=C1");
    // then
    assertThat(body.path("channel").path("is_private").asBoolean()).isTrue();
  }

  @Test
  @DisplayName("없는 채널은 channel_not_found로 거부한다")
  void unknownChannelIsDenied() throws Exception {
    var body = s.response("/api/conversations.info?channel=CUNKNOWN");
    // then
    assertThat(body.path("error").asString()).isEqualTo("channel_not_found");
  }

  @Test
  @DisplayName("멤버 첫 페이지에는 다음 커서가 있다")
  void memberPageHasNextCursor() throws Exception {
    s.memberPages("[[\"U1\"],[\"U2\"]]");
    var body = s.response("/api/conversations.members?channel=C1");
    // then
    assertThat(body.path("response_metadata").path("next_cursor").asString()).isEqualTo("page:1");
  }

  @Test
  @DisplayName("멤버 다음 커서는 다음 페이지의 멤버만 반환한다")
  void memberCursorSelectsNextPage() throws Exception {
    s.memberPages("[[\"U1\"],[\"U2\"]]");
    var body = s.response("/api/conversations.members?channel=C1&cursor=page:1");
    // then
    assertThat(body.path("members").toString()).isEqualTo("[\"U2\"]");
    assertThat(body.has("response_metadata")).isFalse();
  }

  @Test
  @DisplayName("신원 다음 커서는 해당 페이지를 반환한다")
  void userCursorSelectsNextPage() throws Exception {
    s.splitUsersAcrossPages();
    var body = s.response("/api/users.list?cursor=page:1");
    // then
    assertThat(body.path("members").get(0).path("id").asString()).isEqualTo("U2");
  }

  @Test
  @DisplayName("exclude_archived=true이면 보관 채널이 목록에 없다")
  void archivedChannelRespectsInventoryFilter() throws Exception {
    s.channelFlag("is_archived", true);
    var body = s.response("/api/users.conversations?exclude_archived=true");
    // then
    assertThat(body.path("channels")).isEmpty();
  }
}
