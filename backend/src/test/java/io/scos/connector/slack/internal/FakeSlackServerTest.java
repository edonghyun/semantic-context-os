package io.scos.connector.slack.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 가짜 Slack 자체 테스트(F15 C7): 잘못된 요청을 숨기지 않고(TESTING §4 — 미등록 요청은 close에서 실패), 어댑터 계약이 기대는 서빙 규칙
 * (oldest·inclusive 범위, next_cursor 생략, 편집·삭제·추가, 없는 채널·스레드)을 데이터셋(slack-dataset.json)에서 손으로 옮긴 값으로
 * 고정한다.
 */
@DisplayName("가짜 Slack은 데이터셋 규칙대로 답하고 잘못된 요청을 드러낸다(C7)")
class FakeSlackServerTest {
  static final Path DATASET = SlackScenario.FIXTURES.resolve("slack-dataset.json");
  static final String HISTORY = "/api/conversations.history";
  static final String REPLIES = "/api/conversations.replies";
  static final ObjectMapper JSON = new ObjectMapper();

  final FakeSlackServer server = newServer();

  static FakeSlackServer newServer() {
    try {
      return new FakeSlackServer(DATASET);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** 미등록 요청을 일부러 만드는 테스트라 검사 없이 닫는다. */
  @AfterEach
  void stop() {
    server.abandon();
  }

  int send(String method, String path, String authorization) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(server.baseUrl() + path))
            .method(method, HttpRequest.BodyPublishers.noBody())
            .header("Authorization", authorization)
            .build();
    return HttpClient.newHttpClient()
        .send(request, HttpResponse.BodyHandlers.ofString())
        .statusCode();
  }

  String bearer() {
    return "Bearer " + server.token();
  }

  @Nested
  @DisplayName("등록되지 않은 메서드의 요청은")
  class WrongMethod {
    @Test
    @DisplayName("장애 각본이 걸린 경로여도 미등록으로 기록된다")
    void recordedEvenWhenFaulted() throws Exception {
      // given
      server.failNext(HISTORY, 1, FakeSlackServer.Fault.serverError(503));

      // when
      send("POST", HISTORY, bearer());

      // then
      assertThatThrownBy(server::assertNoUnmatched).hasMessageContaining("POST " + HISTORY);
    }

    @Test
    @DisplayName("토큰이 틀려도 미등록으로 기록된다")
    void recordedEvenWithWrongToken() throws Exception {
      // when
      send("POST", HISTORY, "Bearer wrong");

      // then
      assertThatThrownBy(server::assertNoUnmatched).hasMessageContaining("POST " + HISTORY);
    }

    @Test
    @DisplayName("405로 답한다")
    void answersMethodNotAllowed() throws Exception {
      // when
      int status = send("POST", HISTORY, bearer());

      // then
      assertThat(status).isEqualTo(405);
    }
  }

  @Test
  @DisplayName("받은 요청의 Authorization 헤더를 순서대로 기록한다")
  void recordsAuthorizationHeaders() throws Exception {
    // when
    send("GET", "/api/users.conversations?limit=1", bearer());

    // then
    assertThat(server.authorizations()).containsExactly(bearer());
  }

  static final String C1 = "C0000000001";
  static final String THREAD_TS = "1760400100.000000";

  /** GET 요청의 JSON 본문. */
  JsonNode get(String pathAndQuery) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(server.baseUrl() + pathAndQuery))
            .header("Authorization", bearer())
            .build();
    return JSON.readTree(
        HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).body());
  }

  /** 응답 messages의 ts(응답 순서). */
  static List<String> tsOf(JsonNode body) {
    List<String> out = new ArrayList<>();
    body.get("messages").forEach(m -> out.add(m.path("ts").asString("-")));
    return out;
  }

  @Nested
  @DisplayName("oldest를 주면")
  class Oldest {
    @Test
    @DisplayName("inclusive가 아니면 그 ts와 그보다 이른 메시지를 빼고 돌려준다")
    void exclusiveDropsEqualTs() throws Exception {
      // when
      JsonNode body = get(HISTORY + "?channel=" + C1 + "&oldest=" + THREAD_TS);

      // then
      assertThat(tsOf(body)).containsExactly("1760400300.000000");
    }

    @Test
    @DisplayName("inclusive면 그 ts의 메시지도 돌려준다")
    void inclusiveKeepsEqualTs() throws Exception {
      // when
      JsonNode body = get(HISTORY + "?channel=" + C1 + "&oldest=" + THREAD_TS + "&inclusive=true");

      // then
      assertThat(tsOf(body)).containsExactly("1760400300.000000", "1760400100.000000");
    }

    @Test
    @DisplayName("뒤 페이지에 범위 안 메시지가 없으면 next_cursor가 없다")
    void omitsCursorWhenNothingLeft() throws Exception {
      // when
      JsonNode body = get(HISTORY + "?channel=" + C1 + "&oldest=" + THREAD_TS);

      // then
      assertThat(body.has("response_metadata")).isFalse();
    }
  }

  @Test
  @DisplayName("대조군: oldest가 없으면 첫 history 페이지에 next_cursor page:1이 있다")
  void cursorWithoutOldest() throws Exception {
    // when
    JsonNode body = get(HISTORY + "?channel=" + C1);

    // then
    assertThat(body.path("response_metadata").path("next_cursor").asString("")).isEqualTo("page:1");
  }

  @Test
  @DisplayName("참여 채널 목록은 데이터셋 페이지 크기(1)로 나뉘고 다음 커서는 시작 인덱스다")
  void pagesChannels() throws Exception {
    // when
    JsonNode body = get("/api/users.conversations");

    // then
    assertThat(body.get("channels").get(0).get("id").asString()).isEqualTo(C1);
    assertThat(body.path("response_metadata").path("next_cursor").asString("")).isEqualTo("1");
  }

  @Nested
  @DisplayName("스레드 루트 메시지를 지우면")
  class Deleted {
    @Test
    @DisplayName("history에서 사라진다")
    void goneFromHistory() throws Exception {
      // given
      server.deleteMessage(C1, THREAD_TS);

      // when
      JsonNode body = get(HISTORY + "?channel=" + C1);

      // then
      assertThat(tsOf(body)).containsExactly("1760400300.000000", "1760400050.000000");
    }

    @Test
    @DisplayName("replies에서도 사라진다")
    void goneFromReplies() throws Exception {
      // given
      server.deleteMessage(C1, THREAD_TS);

      // when
      JsonNode body = get(REPLIES + "?channel=" + C1 + "&ts=" + THREAD_TS);

      // then
      assertThat(tsOf(body)).containsExactly("1760400120.000000");
    }
  }

  @Test
  @DisplayName("스레드 루트를 지우면 history와 replies에서 지운 2건을 돌려준다")
  void deleteReportsCount() {
    // when
    int deleted = server.deleteMessage(C1, THREAD_TS);

    // then
    assertThat(deleted).isEqualTo(2);
  }

  @Test
  @DisplayName("ts \"\"로 지우면 ts 없는 메시지 1건을 지운다")
  void deletesMessageWithoutTs() {
    // when
    int deleted = server.deleteMessage("C0000000002", "");

    // then
    assertThat(deleted).isEqualTo(1);
  }

  @Test
  @DisplayName("없는 ts를 지우면 0건이다")
  void deletingUnknownTsReportsZero() {
    // when
    int deleted = server.deleteMessage(C1, "1760400999.000000");

    // then
    assertThat(deleted).isZero();
  }

  @Test
  @DisplayName("메시지를 편집하면 text와 edited.ts가 바뀐다")
  void editsMessage() throws Exception {
    // given
    server.editMessage(C1, "1760400300.000000", "고친 본문", "1760400999.000000");

    // when
    JsonNode first = get(HISTORY + "?channel=" + C1).get("messages").get(0);

    // then
    assertThat(first.get("text").asString() + "|" + first.get("edited").get("ts").asString())
        .isEqualTo("고친 본문|1760400999.000000");
  }

  @Test
  @DisplayName("메시지를 더하면 그 history 페이지 끝에 나온다")
  void addsMessage() throws Exception {
    // given
    server.addMessage(C1, 1, "U1", "새 메시지", "1760400001.000000");

    // when
    JsonNode body = get(HISTORY + "?channel=" + C1 + "&cursor=page:1");

    // then
    assertThat(tsOf(body))
        .containsExactly("1760400010.000000", "1760400005.000000", "1760400001.000000");
  }

  @Test
  @DisplayName("데이터셋에 없는 채널의 history는 ok:false channel_not_found다")
  void unknownChannel() throws Exception {
    // when
    JsonNode body = get(HISTORY + "?channel=CMISSING");

    // then
    assertThat(body.get("ok").asBoolean() + " " + body.get("error").asString())
        .isEqualTo("false channel_not_found");
  }

  @Test
  @DisplayName("데이터셋에 없는 스레드의 replies는 ok:false thread_not_found다")
  void unknownThread() throws Exception {
    // when
    JsonNode body = get(REPLIES + "?channel=" + C1 + "&ts=1760400999.000000");

    // then
    assertThat(body.get("ok").asBoolean() + " " + body.get("error").asString())
        .isEqualTo("false thread_not_found");
  }

  @Nested
  @DisplayName("채널이 사라지면")
  class ChannelRemoved {
    @BeforeEach
    void givenChannelRemoved() {
      server.removeChannel(C1);
    }

    @Test
    @DisplayName("참여 목록에는 남는다")
    void staysInListing() throws Exception {
      // when
      JsonNode body = get("/api/users.conversations");

      // then
      assertThat(body.get("channels").get(0).get("id").asString()).isEqualTo(C1);
    }

    @Test
    @DisplayName("history는 ok:false channel_not_found다")
    void historyIsChannelNotFound() throws Exception {
      // when
      JsonNode body = get(HISTORY + "?channel=" + C1);

      // then
      assertThat(body.get("ok").asBoolean() + " " + body.get("error").asString())
          .isEqualTo("false channel_not_found");
    }
  }

  @Nested
  @DisplayName("스레드가 사라지면")
  class ThreadRemoved {
    @BeforeEach
    void givenThreadRemoved() {
      server.removeThread(C1, THREAD_TS);
    }

    @Test
    @DisplayName("루트 메시지는 history에 reply_count와 함께 남는다")
    void rootStaysInHistory() throws Exception {
      // when
      JsonNode root = get(HISTORY + "?channel=" + C1).get("messages").get(1);

      // then
      assertThat(root.get("ts").asString() + " " + root.get("reply_count").asInt())
          .isEqualTo(THREAD_TS + " 2");
    }

    @Test
    @DisplayName("replies는 ok:false thread_not_found다")
    void repliesIsThreadNotFound() throws Exception {
      // when
      JsonNode body = get(REPLIES + "?channel=" + C1 + "&ts=" + THREAD_TS);

      // then
      assertThat(body.get("ok").asBoolean() + " " + body.get("error").asString())
          .isEqualTo("false thread_not_found");
    }
  }
}
