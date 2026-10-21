package io.scos.connector.googledrive.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.scos.collection.internal.credentials.SchemeCredentialResolver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * OAuth 자격 증명 JSON(TS GoogleDriveApiClient.session): 객체이고 세 키가 비어 있지 않은 문자열이어야 한다. 기대 메시지는 TS 표다.
 */
@DisplayName("Google OAuth 자격 증명 JSON은 TS 표와 같이 받고 거부한다")
class GoogleCredentialTest {
  static Stream<Arguments> invalidCredentials() {
    return StreamSupport.stream(GoogleDriveFixtures.cases().get("cases").spliterator(), false)
        .filter(c -> !c.get("credential").isNull())
        .map(
            c ->
                Arguments.of(
                    c.get("label").asString(),
                    c.get("credential").asString(),
                    c.get("error").get("message").asString()));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidCredentials")
  @DisplayName("TS와 같은 메시지로 거부한다")
  void rejectsLikeTs(String label, String credential, String message) {
    // when
    Throwable thrown =
        catchThrowable(() -> GoogleCredential.parse(GoogleDriveFixtures.JSON, credential));

    // then
    assertThat(thrown).isInstanceOf(GoogleProtocolException.class).hasMessage(message);
  }

  @Test
  @DisplayName("운영 설정 스크립트가 쓰는 한 줄 JSON을 읽는다")
  void readsOneLineJson() {
    // when
    GoogleCredential credential =
        GoogleCredential.parse(GoogleDriveFixtures.JSON, GoogleDriveFixtures.credentialJson());

    // then
    assertThat(credential.clientId())
        .isEqualTo(GoogleDriveFixtures.dataset().get("credential").get("client_id").asString());
  }

  @Test
  @DisplayName("run에는 TS와 같은 errorKind Error를 남긴다")
  void runErrorKindIsTsError() {
    // when
    GoogleProtocolException thrown =
        catchThrowableOfType(
            GoogleProtocolException.class,
            () -> GoogleCredential.parse(GoogleDriveFixtures.JSON, "[]"));

    // then
    assertThat(thrown.runErrorKind()).isEqualTo("Error");
  }

  /** 들여쓴 OAuth JSON 파일(TS는 읽는다 — 손으로 쓴 자격 증명). */
  static final String INDENTED_CREDENTIAL =
      "{\n  \"client_id\": \"c\",\n  \"client_secret\": \"s\",\n  \"refresh_token\": \"r\"\n}\n";

  /** gcloud ADC(authorized_user) 모양: 들여쓴 JSON, 그 밖의 키. */
  static final String ADC_CREDENTIAL =
      "{\n  \"client_id\": \"c\",\n  \"client_secret\": \"s\",\n  \"quota_project_id\": \"q\",\n  \"refresh_token\": \"adc-refresh\",\n  \"type\": \"authorized_user\"\n}\n";

  @Test
  @DisplayName("들여쓴 자격 증명 파일(gcloud ADC 모양)도 file: 해석기를 거쳐 읽힌다")
  void readsIndentedCredentialFile(@TempDir Path dir) throws Exception {
    // given
    Path file = Files.writeString(dir.resolve("drive.json"), ADC_CREDENTIAL);

    // when
    GoogleCredential credential =
        GoogleCredential.parse(
            GoogleDriveFixtures.JSON,
            SchemeCredentialResolver.standard(Map.of()).resolve("file:" + file));

    // then
    assertThat(credential.refreshToken()).isEqualTo("adc-refresh");
  }

  @Test
  @DisplayName("들여쓴 자격 증명 JSON 자체는 세션이 읽는다")
  void indentedCredentialJsonParses() {
    // when
    GoogleCredential credential =
        GoogleCredential.parse(GoogleDriveFixtures.JSON, INDENTED_CREDENTIAL);

    // then
    assertThat(credential.refreshToken()).isEqualTo("r");
  }

  /** 운영 설정 스크립트(setup-google-drive-oauth.py)가 쓰는 모양: json.dump(공백 있는 한 줄) + 끝 줄바꿈. */
  static final String SCRIPT_CREDENTIAL =
      "{\"client_id\": \"c\", \"client_secret\": \"script-secret\", \"refresh_token\": \"r\"}\n";

  @Test
  @DisplayName("운영 설정 스크립트가 쓴 파일은 file: 해석기를 거쳐 읽힌다")
  void readsScriptWrittenFile(@TempDir Path dir) throws Exception {
    // given
    Path file = Files.writeString(dir.resolve("drive.json"), SCRIPT_CREDENTIAL);

    // when
    GoogleCredential credential =
        GoogleCredential.parse(
            GoogleDriveFixtures.JSON,
            SchemeCredentialResolver.standard(Map.of()).resolve("file:" + file));

    // then
    assertThat(credential.clientSecret()).isEqualTo("script-secret");
  }
}
