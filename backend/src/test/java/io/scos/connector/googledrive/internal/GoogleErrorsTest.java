package io.scos.connector.googledrive.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.connector.googledrive.internal.GoogleSessionScenario.FailureView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

/** HTTP 응답이 없는 실패와 클라이언트가 만든 고장의 분류(TS classifyGoogleError). 기대값은 TS 표(expected-client.json)다. */
@DisplayName("응답 없는 실패와 클라이언트 고장은 TS classifyGoogleError와 같다")
class GoogleErrorsTest {
  @Test
  @DisplayName("전송 실패는 재시도할 의존 장애다")
  void transportFailureIsRetryable() {
    // when
    FailureView failure =
        FailureView.of(GoogleErrors.classify(new ResourceAccessException("connection refused")));

    // then
    assertThat(failure)
        .isEqualTo(FailureView.expected(GoogleDriveFixtures.client().get("transportFailure")));
  }

  @Test
  @DisplayName("본문 상한 초과는 전송 실패와 같이 분류된다")
  void contentTooLargeIsTransportFailure() {
    // when
    FailureView failure =
        FailureView.of(GoogleErrors.classify(new GoogleContentTooLargeException()));

    // then
    assertThat(failure)
        .isEqualTo(FailureView.expected(GoogleDriveFixtures.client().get("contentTooLarge")));
  }

  @Test
  @DisplayName("크기가 맞지 않는 다운로드는 재시도할 502다")
  void incompleteDownloadIsRetryable() {
    // when
    FailureView failure =
        FailureView.of(
            GoogleErrors.classify(
                GoogleApiException.of(502, GoogleApiException.Reason.INCOMPLETE_DOWNLOAD)));

    // then
    assertThat(failure)
        .isEqualTo(FailureView.expected(GoogleDriveFixtures.client().get("incompleteDownload")));
  }
}
