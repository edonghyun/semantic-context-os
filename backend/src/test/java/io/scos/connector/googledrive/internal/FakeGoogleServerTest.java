package io.scos.connector.googledrive.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.support.FakeHttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * 가짜 Google 자체 테스트(F15 C7): 잘못된 요청을 숨기지 않고(TESTING §3.6 2층·§4: 데이터셋에 없는 쿼리, 틀린 Bearer, 틀린 토큰 폼),
 * kill·재개· 변경 피드 테스트가 기대는 각본(crashAt, 둘째 run 라우트, 덮어쓰기 우선순위, 마지막 응답 반복)이 데이터셋대로 답한다.
 */
@DisplayName("가짜 Google은 데이터셋 규칙대로 답하고 잘못된 요청을 드러낸다(C7)")
class FakeGoogleServerTest {
  static final String ABOUT = "/drive/v3/about";

  final FakeGoogleServer google = newServer();

  static FakeGoogleServer newServer() {
    try {
      return new FakeGoogleServer(GoogleDriveFixtures.dataset());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @AfterEach
  void stop() {
    google.abandon();
  }

  int get(String pathAndQuery, String authorization) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(
                URI.create(google.baseUrl() + FakeGoogleServer.prefixed("drive", pathAndQuery)))
            .header("Authorization", authorization)
            .build();
    return HttpClient.newHttpClient()
        .send(request, HttpResponse.BodyHandlers.discarding())
        .statusCode();
  }

  int postToken(String form) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(
                URI.create(google.baseUrl() + FakeGoogleServer.prefixed("oauth", "/token")))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build();
    return HttpClient.newHttpClient()
        .send(request, HttpResponse.BodyHandlers.discarding())
        .statusCode();
  }

  @Test
  @DisplayName("데이터셋에 없는 쿼리는 미등록으로 남아 검사에서 실패한다")
  void failsOnUnknownQuery() throws Exception {
    // when
    get(ABOUT + "?fields=other", "Bearer " + google.accessToken());

    // then
    assertThatThrownBy(google::assertNoUnmatched).hasMessageContaining(ABOUT + "?fields=other");
  }

  @Test
  @DisplayName("데이터셋 access token이 아니면 401로 답한다")
  void rejectsWrongBearer() throws Exception {
    // when
    int status = get(ABOUT + "?fields=user(emailAddress,permissionId)", "Bearer other");

    // then
    assertThat(status).isEqualTo(401);
  }

  @Test
  @DisplayName("토큰 폼이 데이터셋 자격 증명과 다르면 400으로 답한다")
  void rejectsWrongTokenForm() throws Exception {
    // when
    int status = postToken("client_id=x&grant_type=refresh_token");

    // then
    assertThat(status).isEqualTo(400);
  }

  static final String START_PAGE_TOKEN = "/drive/v3/changes/startPageToken";
  static final String DRIVES = "/drive/v3/drives";
  static final String PDF = "/drive/v3/files/pdf-1";
  static final String SHEET_2 = "/drive/v3/files/sheet-2";
  static final String FILE_FIELDS =
      "id,name,mimeType,description,parents,driveId,webViewLink,createdTime,modifiedTime,version,size,md5Checksum,owners(displayName,emailAddress),shared,trashed,capabilities(canDownload),shortcutDetails";

  /** drive origin 경로에 쿼리를 붙여 데이터셋 access token으로 보낸다. */
  HttpResponse<String> drive(String path, Map<String, String> params) throws Exception {
    StringBuilder query = new StringBuilder();
    params.forEach(
        (k, v) ->
            query
                .append(query.isEmpty() ? "?" : "&")
                .append(URLEncoder.encode(k, StandardCharsets.UTF_8))
                .append('=')
                .append(URLEncoder.encode(v, StandardCharsets.UTF_8)));
    HttpRequest request =
        HttpRequest.newBuilder(
                URI.create(google.baseUrl() + FakeGoogleServer.prefixed("drive", path) + query))
            .header("Authorization", "Bearer " + google.accessToken())
            .build();
    return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
  }

  static Map<String, String> params(String... keyValues) {
    Map<String, String> out = new LinkedHashMap<>();
    for (int i = 0; i < keyValues.length; i += 2) out.put(keyValues[i], keyValues[i + 1]);
    return out;
  }

  /** 데이터셋 라우트 표(routes·secondRunRoutes)에서 이 경로·쿼리의 마지막 응답 본문. */
  static String datasetText(String table, String path, Map<String, String> params) {
    for (JsonNode r : GoogleDriveFixtures.dataset().get(table)) {
      Map<String, String> p = new LinkedHashMap<>();
      r.get("params").properties().forEach(e -> p.put(e.getKey(), e.getValue().asString()));
      if (r.get("path").asString().equals(path) && p.equals(params)) {
        JsonNode responses = r.get("responses");
        return responses.get(responses.size() - 1).get("text").asString();
      }
    }
    throw new IllegalStateException("given 준비 실패: 데이터셋에 " + table + " " + path + " 라우트가 없다");
  }

  @Test
  @DisplayName("차례 응답을 다 쓰면 마지막 응답을 반복한다(drives: 503 뒤 200)")
  void repeatsLastResponse() throws Exception {
    // given
    Map<String, String> q = params("pageSize", "100", "fields", "nextPageToken,drives(id,name)");

    // when
    int first = drive(DRIVES, q).statusCode();
    int second = drive(DRIVES, q).statusCode();
    int third = drive(DRIVES, q).statusCode();

    // then
    assertThat(new int[] {first, second, third}).containsExactly(503, 200, 200);
  }

  @Nested
  @DisplayName("데이터 요청 상한을 넘으면")
  class RequestLimit {

    final FakeGoogleServer small = newServer(2);
    int lastWithinLimit;
    int overLimit;

    static FakeGoogleServer newServer(int maxRequests) {
      try {
        return new FakeGoogleServer(GoogleDriveFixtures.dataset(), maxRequests);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    @BeforeEach
    void givenLimitSpent() throws Exception {
      aboutOn(small);
      lastWithinLimit = aboutOn(small);
      overLimit = aboutOn(small);
    }

    @AfterEach
    void stopSmall() {
      small.abandon();
    }

    int aboutOn(FakeGoogleServer server) throws Exception {
      HttpRequest request =
          HttpRequest.newBuilder(
                  URI.create(
                      server.baseUrl()
                          + FakeGoogleServer.prefixed("drive", ABOUT)
                          + "?fields=user%28emailAddress%2CpermissionId%29"))
              .header("Authorization", "Bearer " + server.accessToken())
              .build();
      return HttpClient.newHttpClient()
          .send(request, HttpResponse.BodyHandlers.discarding())
          .statusCode();
    }

    @Test
    @DisplayName("대조군: 상한째 요청까지는 데이터셋으로 답한다")
    void answersWithinLimit() {
      // then
      assertThat(lastWithinLimit).isEqualTo(200);
    }

    @Test
    @DisplayName("상한 다음 요청은 비재시도 400으로 답한다")
    void answersBadRequest() {
      // then
      assertThat(overLimit).isEqualTo(400);
    }
  }

  @Nested
  @DisplayName("crashAt을 걸면")
  class CrashAt {
    final Map<String, String> userStart = params("supportsAllDrives", "true");

    @BeforeEach
    void givenCrashAtUserStartPageToken() {
      google.crashAt(START_PAGE_TOKEN, userStart);
    }

    @Test
    @DisplayName("경로와 쿼리가 같은 요청은 401이다")
    void crashesOnExactRequest() throws Exception {
      // when
      int status = drive(START_PAGE_TOKEN, userStart).statusCode();

      // then
      assertThat(status).isEqualTo(401);
    }

    @Test
    @DisplayName("같은 경로라도 쿼리가 다르면 데이터셋으로 답한다")
    void otherQueryIsUntouched() throws Exception {
      // when
      int status =
          drive(START_PAGE_TOKEN, params("supportsAllDrives", "true", "driveId", "team-1"))
              .statusCode();

      // then
      assertThat(status).isEqualTo(200);
    }

    @Test
    @DisplayName("recover 뒤에는 데이터셋으로 답한다")
    void recoverClearsCrash() throws Exception {
      // given
      google.recover();

      // when
      int status = drive(START_PAGE_TOKEN, userStart).statusCode();

      // then
      assertThat(status).isEqualTo(200);
    }
  }

  @Nested
  @DisplayName("둘째 run 라우트로 바꾸면")
  class SecondRun {
    final Map<String, String> sheetMetadata =
        params("supportsAllDrives", "true", "fields", FILE_FIELDS);

    @BeforeEach
    void givenSecondRun() {
      google.useSecondRunRoutes();
    }

    @Test
    @DisplayName("둘째 run 라우트가 있는 요청은 그 응답이다")
    void secondRunRouteWins() throws Exception {
      // when
      String body = drive(SHEET_2, sheetMetadata).body();

      // then
      assertThat(body).isEqualTo(datasetText("secondRunRoutes", SHEET_2, sheetMetadata));
    }

    @Test
    @DisplayName("둘째 run 라우트가 없는 요청은 첫 run 라우트로 답한다")
    void fallsBackToFirstRun() throws Exception {
      // when
      int status = drive(ABOUT, params("fields", "user(emailAddress,permissionId)")).statusCode();

      // then
      assertThat(status).isEqualTo(200);
    }

    @Test
    @DisplayName("경로 덮어쓰기는 둘째 run 라우트보다 먼저다")
    void overrideBeatsSecondRun() throws Exception {
      // given
      google.respond(SHEET_2, new FakeHttpServer.Response(500, "{}", Map.of()));

      // when
      int status = drive(SHEET_2, sheetMetadata).statusCode();

      // then
      assertThat(status).isEqualTo(500);
    }
  }

  @Test
  @DisplayName("대조군: 둘째 run으로 바꾸기 전에는 첫 run 응답이다")
  void firstRunBeforeSwitch() throws Exception {
    // given
    Map<String, String> sheetMetadata = params("supportsAllDrives", "true", "fields", FILE_FIELDS);

    // when
    String body = drive(SHEET_2, sheetMetadata).body();

    // then
    assertThat(body).isEqualTo(datasetText("routes", SHEET_2, sheetMetadata));
  }

  @Nested
  @DisplayName("원본 다운로드만 덮어쓰면")
  class MediaOverride {
    @BeforeEach
    void givenMediaOverride() {
      google.respondMedia(PDF, new FakeHttpServer.Response(500, "{}", Map.of()));
    }

    @Test
    @DisplayName("alt=media 요청은 덮어쓴 응답이다")
    void mediaRequestIsOverridden() throws Exception {
      // when
      int status = drive(PDF, params("alt", "media", "supportsAllDrives", "true")).statusCode();

      // then
      assertThat(status).isEqualTo(500);
    }

    @Test
    @DisplayName("같은 경로의 메타데이터 요청은 데이터셋으로 답한다")
    void metadataRequestIsUntouched() throws Exception {
      // when
      int status =
          drive(PDF, params("supportsAllDrives", "true", "fields", FILE_FIELDS)).statusCode();

      // then
      assertThat(status).isEqualTo(200);
    }
  }

  @Test
  @DisplayName("Content-Type이 폼이 아닌 토큰 요청은 폼이 맞아도 400이다")
  void rejectsTokenWithoutFormContentType() throws Exception {
    // given
    HttpRequest request =
        HttpRequest.newBuilder(
                URI.create(google.baseUrl() + FakeGoogleServer.prefixed("oauth", "/token")))
            .header("Content-Type", "text/plain")
            .POST(HttpRequest.BodyPublishers.ofString(DATASET_FORM))
            .build();

    // when
    int status =
        HttpClient.newHttpClient()
            .send(request, HttpResponse.BodyHandlers.discarding())
            .statusCode();

    // then
    assertThat(status).isEqualTo(400);
  }

  @Test
  @DisplayName("대조군: 데이터셋 폼과 같은 토큰 요청은 200이다")
  void acceptsDatasetTokenForm() throws Exception {
    // when
    int status = postToken(DATASET_FORM);

    // then
    assertThat(status).isEqualTo(200);
  }

  /** 데이터셋 oauth.form을 옮겨 적은 폼. */
  static final String DATASET_FORM =
      "client_id=drive-client.apps.googleusercontent.com&client_secret=drive-secret-synthetic"
          + "&refresh_token=1%2F%2Fdrive-refresh-synthetic&grant_type=refresh_token";
}
