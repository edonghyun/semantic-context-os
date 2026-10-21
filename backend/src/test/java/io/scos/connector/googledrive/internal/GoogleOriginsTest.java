package io.scos.connector.googledrive.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;

/**
 * 운영 origin 표(GoogleOrigins.GOOGLE)가 TS의 고정 origin 표·토큰 끝점(데이터셋 `origins`, TS
 * google-drive-api-client.ts)과 같다. 테스트 조립은 가짜 서버를 쓰므로 운영 값은 이 테스트만 지킨다.
 */
@DisplayName("운영 Google origin 표는 TS 고정 origin과 같다")
class GoogleOriginsTest {
  static final JsonNode TS = GoogleDriveFixtures.dataset().get("origins");

  @ParameterizedTest(name = "{0}")
  @EnumSource(GoogleService.class)
  @DisplayName("서비스마다 TS와 같은 Google origin을 부른다")
  void serviceOriginsMatchTs(GoogleService service) {
    // when
    URI origin = GoogleOrigins.GOOGLE.of(service);

    // then
    assertThat(origin.toString())
        .isEqualTo(TS.get(service.name().toLowerCase(Locale.ROOT)).asString());
  }

  @Test
  @DisplayName("토큰 갱신은 TS와 같은 OAuth 토큰 끝점이다")
  void tokenEndpointMatchesTs() {
    // when
    String endpoint = GoogleOrigins.GOOGLE.oauth() + GoogleDriveSession.TOKEN_PATH;

    // then
    assertThat(endpoint).isEqualTo("https://oauth2.googleapis.com/token");
  }
}
