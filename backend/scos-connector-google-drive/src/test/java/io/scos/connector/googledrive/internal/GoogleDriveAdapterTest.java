package io.scos.connector.googledrive.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.connector.googledrive.internal.GoogleDriveScenario.Collection;
import io.scos.connector.googledrive.internal.GoogleDriveViews.ErrorView;
import io.scos.support.FakeHttpServer.Response;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * TS fixture로 만들 수 없는 크기의 계약(TS GoogleDriveSession.json의 50 MiB 읽기 상한): 선택 본문이 상한을 넘으면 수집 제한으로 남고,
 * 필수 응답이 넘으면 run이 TS 이름 GoogleContentTooLarge로 실패한다.
 */
@DisplayName("Drive 본문 50 MiB 읽기 상한은 TS GoogleDriveSession과 같다")
class GoogleDriveAdapterTest {
  /** JSON 읽기 상한(50 MiB)을 1바이트 넘는 본문 — 상한 + 1바이트만 읽고 멈춘다. */
  static final Response OVERSIZED_JSON = oversized();

  static final String DOC = "doc-1";
  static final String TOO_LARGE = "Google content exceeds the configured capture limit";

  final GoogleDriveScenario drive = new GoogleDriveScenario();

  @AfterEach
  void tearDown() {
    drive.close();
  }

  static Response oversized() {
    byte[] body = new byte[Math.toIntExact(GoogleDriveSession.MAX_JSON_BYTES) + 1];
    Arrays.fill(body, (byte) ' ');
    body[0] = '{';
    body[body.length - 1] = '}';
    return Response.binary(200, body, Map.of("Content-Type", "application/json"));
  }

  @Nested
  @DisplayName("Docs 본문이 50 MiB를 넘으면")
  class OversizedOptionalBody {
    @Test
    @DisplayName("본문 제한으로 남긴다")
    void recordsContentGap() {
      // given
      drive.pathAnswers("/v1/documents/" + DOC, OVERSIZED_JSON);

      // when
      Collection java = drive.collectFrom(GoogleDriveScenario.resumeFile(DOC));

      // then
      assertThat(java.limitations()).containsExactly(List.of("content: " + TOO_LARGE));
    }
  }

  @Nested
  @DisplayName("about 응답이 50 MiB를 넘으면")
  class OversizedRequiredBody {
    @Test
    @DisplayName("run이 TS 이름 GoogleContentTooLarge로 실패한다")
    void failsWithTsName() {
      // given
      drive.pathAnswers("/drive/v3/about", OVERSIZED_JSON);

      // when
      Collection java = drive.collect();

      // then
      assertThat(java.error())
          .hasValueSatisfying(
              e ->
                  assertThat(e)
                      .extracting(ErrorView::name, ErrorView::message)
                      .containsExactly("GoogleContentTooLarge", TOO_LARGE));
    }
  }
}
