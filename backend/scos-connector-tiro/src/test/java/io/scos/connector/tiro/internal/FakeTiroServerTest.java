package io.scos.connector.tiro.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.connector.tiro.TiroAssembly;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * 가짜 Tiro 자체 테스트(F15 C7): Tiro fixture·계약 테스트가 기대는 데이터셋 서빙 규칙(tiro-dataset.json, TS
 * make-tiro-fixture.ts의 fakeFetch와 같은 규칙)과 장애 각본이 틀린 입력에서 제대로 답하는지 본다. 기대값은 데이터셋을 읽어 손으로 옮겨 적었다.
 */
@DisplayName("가짜 Tiro는 데이터셋 규칙과 장애 각본대로 답한다(C7)")
class FakeTiroServerTest {
  static final String LIST = "/v1/external/workspaces/ws-synthetic/notes";
  static final String KEY = "Bearer tiro-synthetic-key";

  /** 요청 하나의 hang 방지 상한. */
  static final Duration WAIT_LIMIT = Duration.ofSeconds(5);

  final FakeTiroServer server = TiroAssembly.server();

  @AfterEach
  void close() {
    server.close();
  }

  HttpResponse<String> get(String pathAndQuery, String authorization) throws Exception {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(URI.create(server.baseUrl() + pathAndQuery))
                .header("Authorization", authorization)
                .timeout(WAIT_LIMIT)
                .build(),
            HttpResponse.BodyHandlers.ofString());
  }

  HttpResponse<String> get(String pathAndQuery) throws Exception {
    return get(pathAndQuery, KEY);
  }

  /** 목록 응답에 실린 노트 guid(응답 순서). */
  static List<String> guids(HttpResponse<String> listing) {
    JsonNode body = TiroAssembly.JSON.readTree(listing.body());
    List<String> out = new ArrayList<>();
    body.get("content").forEach(n -> out.add(n.get("guid").asString()));
    return out;
  }

  @Test
  @DisplayName("데이터셋 폴더의 첫 목록 페이지는 n-alpha·n-beta·n-locked와 다음 커서 page:1이다")
  void servesFirstListPage() throws Exception {
    // when
    HttpResponse<String> response = get(LIST + "?size=20&folderId=folder-1");

    // then
    assertThat(guids(response)).containsExactly("n-alpha", "n-beta", "n-locked");
    assertThat(TiroAssembly.JSON.readTree(response.body()).get("nextCursor").asString())
        .isEqualTo("page:1");
  }

  @Test
  @DisplayName("커서 page:1은 둘째 목록 페이지다")
  void servesPageByCursor() throws Exception {
    // when
    HttpResponse<String> response = get(LIST + "?size=20&folderId=folder-1&cursor=page:1");

    // then
    assertThat(guids(response)).containsExactly("n-gamma", "n-delta", "n-gone");
  }

  @Test
  @DisplayName("데이터셋 폴더가 아니면 400이다")
  void rejectsOtherFolder() throws Exception {
    // when
    int status = get(LIST + "?size=20&folderId=folder-2").statusCode();

    // then
    assertThat(status).isEqualTo(400);
  }

  @Test
  @DisplayName("키가 다르면 401이다")
  void rejectsWrongKey() throws Exception {
    // when
    int status = get(LIST + "?size=20&folderId=folder-1", "Bearer other").statusCode();

    // then
    assertThat(status).isEqualTo(401);
  }

  @Test
  @DisplayName("목록에만 있고 데이터셋에 없는 노트(n-gone)의 상세는 404다")
  void unknownListedNoteIsNotFound() throws Exception {
    // when
    int status = get("/v1/external/notes/n-gone").statusCode();

    // then
    assertThat(status).isEqualTo(404);
  }

  @Test
  @DisplayName("상세에 status를 둔 노트(n-locked)는 그 상태 403으로 답한다")
  void lockedNoteAnswersItsStatus() throws Exception {
    // when
    int status = get("/v1/external/notes/n-locked").statusCode();

    // then
    assertThat(status).isEqualTo(403);
  }

  @Test
  @DisplayName("상세가 rawJson인 노트(n-delta)는 그 원문 그대로 답한다")
  void rawJsonNoteAnswersVerbatim() throws Exception {
    // given
    JsonNode dataset = TiroAssembly.JSON.readTree(Files.readString(TiroAssembly.DATASET));
    String raw = dataset.get("notes").get("n-delta").get("detail").get("rawJson").asString();

    // when
    String body = get("/v1/external/notes/n-delta").body();

    // then
    assertThat(body).isEqualTo(raw);
  }

  @Nested
  @DisplayName("둘째 목록 페이지에 장애를 두 번 걸면")
  class CursorFault {
    static final String SECOND_PAGE = LIST + "?size=20&folderId=folder-1&cursor=page:1";

    @Test
    @DisplayName("그 커서의 요청 두 번은 장애로, 셋째는 데이터셋으로 답한다")
    void faultsOnlyForBudget() throws Exception {
      // given
      server.failOnCursor(LIST, "page:1", 2, FakeTiroServer.Fault.status(503));

      // when
      int first = get(SECOND_PAGE).statusCode();
      int second = get(SECOND_PAGE).statusCode();
      int third = get(SECOND_PAGE).statusCode();

      // then
      assertThat(new int[] {first, second, third}).containsExactly(503, 503, 200);
    }

    @Test
    @DisplayName("다른 커서(첫 페이지)는 장애 없이 답한다")
    void otherCursorIsUntouched() throws Exception {
      // given
      server.failOnCursor(LIST, "page:1", 2, FakeTiroServer.Fault.status(503));

      // when
      int status = get(LIST + "?size=20&folderId=folder-1").statusCode();

      // then
      assertThat(status).isEqualTo(200);
    }
  }

  @Nested
  @DisplayName("목록에서 n-gamma를 빼면")
  class RemovedFromListing {
    @Test
    @DisplayName("둘째 목록 페이지에 n-gamma가 없다")
    void listingOmitsNote() throws Exception {
      // given
      server.removeFromListing("n-gamma");

      // when
      HttpResponse<String> response = get(LIST + "?size=20&folderId=folder-1&cursor=page:1");

      // then
      assertThat(guids(response)).containsExactly("n-delta", "n-gone");
    }

    @Test
    @DisplayName("n-gamma 상세는 그대로 답한다")
    void detailStillAnswers() throws Exception {
      // given
      server.removeFromListing("n-gamma");

      // when
      HttpResponse<String> response = get("/v1/external/notes/n-gamma");

      // then
      assertThat(TiroAssembly.JSON.readTree(response.body()).get("guid").asString())
          .isEqualTo("n-gamma");
    }
  }

  @Test
  @DisplayName("answer로 건 본문은 데이터셋 응답을 덮어쓴다")
  void answerOverridesDataset() throws Exception {
    // given
    server.answer("/v1/external/notes/n-alpha", "{\"overridden\":true}");

    // when
    String body = get("/v1/external/notes/n-alpha").body();

    // then
    assertThat(body).isEqualTo("{\"overridden\":true}");
  }
}
