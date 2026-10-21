package io.scos.connector.googledrive.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.connector.googledrive.internal.GoogleDriveScenario.Collection;
import io.scos.support.FakeHttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 토큰 끝점이 헤더로 보낼 수 없는 access token을 주면(CONTRACTS Drive 편차) 공급자 호출 실행기를 거친 수집도 재시도하지 않는다: Bearer 헤더
 * 지점의 설정 오류(invalid_configuration)를 오류 분류기가 다시 분류하지 않고 그대로 넘긴다. 세션을 직접 부르는 세션 테스트와 달리 분류기·실행기를 지난다.
 */
@DisplayName("Drive 토큰 끝점이 헤더로 못 보낼 토큰을 주면 재시도 없이 설정 오류다")
class GoogleDriveUnsendableTokenTest {
  final GoogleDriveScenario drive = new GoogleDriveScenario();

  @BeforeEach
  void givenTokenEndpointReturnsUnsendableToken() {
    drive.pathAnswers(
        GoogleSessionScenario.TOKEN,
        FakeHttpServer.ok("{\"access_token\":\"a\\r\\nb\",\"expires_in\":3600}"));
  }

  @AfterEach
  void tearDown() {
    drive.close();
  }

  @Test
  @DisplayName("재시도 대기 없이 끝난다")
  void doesNotRetry() {
    // when
    drive.collect();

    // then
    assertThat(drive.waits()).isEmpty();
  }

  @Test
  @DisplayName("설정 오류(invalid_configuration)로 끝난다")
  void endsInvalidConfiguration() {
    // when
    Collection java = drive.collect();

    // then
    assertThat(java.failureKind()).contains("invalid_configuration");
  }
}
