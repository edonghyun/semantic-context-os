package io.scos.connector.jira.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.collection.internal.TsRawViews.RejectionView;
import io.scos.connector.jira.internal.JiraScenario.Collection;
import io.scos.connector.jira.internal.JiraTsRuns.RequestView;
import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.RawContent;
import io.scos.spi.connector.RawItem;
import io.scos.spi.connector.RawMutation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Jira 첨부 원본(TS jira-attachments, F5 T5.5): redirect=false 바이트를 blob(바이트 sha256)으로 담고, 크기를 미리 알면 50
 * MiB 초과는 내려받지 않고 거부한다. 받은 첨부 id는 체크포인트 이력에 남아 다음 수집에서 다시 받지 않는다. 일시 장애는 재시도한다.
 */
@DisplayName("Jira 첨부 원본은 50 MiB 안에서 한 번만 blob으로 받는다(F5 T5.5)")
class JiraAttachmentTest {
  static final String PNG = "50002";

  /** 데이터셋 PNG의 바이트 수(jira-dataset.json의 size). */
  static final long PNG_SIZE = 16;

  /** 50 MiB를 넘는 데이터셋 첨부와 그 크기(60 MiB). */
  static final String OVERSIZED = "50003";

  static final long OVERSIZED_SIZE = 60L * 1024 * 1024;

  /** 첨부 상한 50 MiB를 손으로 쓴 값(제품 상수를 기대값으로 쓰지 않는다). */
  static final long FIFTY_MIB = 52_428_800L;

  /** TS jira-adapter의 거부 메시지 원문. */
  static final String TS_ATTACHMENT_TOO_LARGE =
      "Attachment exceeds the 50 MiB raw capture limit; original not downloaded";

  static final String TS_UNAVAILABLE =
      "Jira resource unavailable to this account or API; coverage is incomplete";

  /** 이전 수집에서 이미 받은 다른 첨부. */
  static final String EARLIER_DOWNLOAD = "8";

  static final String ATTACHMENT_PATH = "/rest/api/3/attachment/content/";

  /** 첨부 50001을 가진 데이터셋 이슈. */
  static final String ISSUE_40001 = "/rest/api/3/issue/40001";

  /** 데이터셋 PNG(PNG_SIZE)보다도 작은 캡처 상한. */
  static final long TINY_CAPTURE_LIMIT = 8;

  final JiraScenario jira = new JiraScenario();

  @AfterEach
  void tearDown() {
    jira.close();
  }

  static String attachmentWork(String id, long size) {
    return "[{\"kind\":\"attachment\",\"id\":\""
        + id
        + "\",\"key\":\"PAY-4\",\"size\":"
        + size
        + "}]";
  }

  static RawContent.Blob blobOf(Collection collection) {
    RawItem item =
        ((RawMutation.Upsert) collection.pages().getFirst().mutations().getFirst()).item();
    return (RawContent.Blob) item.content().orElseThrow();
  }

  @Nested
  @DisplayName("50 MiB 이하로 알려진 첨부를 수집하면")
  class WithinLimit {
    @Test
    @DisplayName("원본 바이트를 그 sha256으로 주소 매긴 blob에 담는다")
    void capturesBytesAsBlob() {
      // when
      Collection java =
          jira.collectFrom(JiraScenario.checkpointWith(attachmentWork(PNG, PNG_SIZE), "[]"));

      // then
      assertThat(blobOf(java).sha256()).isEqualTo(JiraFixtures.PNG_SHA256);
    }

    @Test
    @DisplayName("50 MiB와 같다고 알려진 첨부는 내려받는다")
    void downloadsAtLimit() {
      // when
      jira.collectFrom(JiraScenario.checkpointWith(attachmentWork(PNG, FIFTY_MIB), "[]"));

      // then
      assertThat(jira.requests())
          .extracting(RequestView::path)
          .containsExactly(ATTACHMENT_PATH + PNG);
    }
  }

  @Nested
  @DisplayName("50 MiB를 넘는다고 알려진 첨부를 수집하면")
  class OverLimit {
    @Test
    @DisplayName("내려받지 않는다")
    void skipsDownloadOverLimit() {
      // when
      jira.collectFrom(
          JiraScenario.checkpointWith(attachmentWork(OVERSIZED, OVERSIZED_SIZE), "[]"));

      // then
      assertThat(jira.requests()).isEmpty();
    }

    @Test
    @DisplayName("누락 이유를 TS 원문으로 남긴다")
    void rejectsOverLimitWithReason() {
      // when
      Collection java =
          jira.collectFrom(
              JiraScenario.checkpointWith(attachmentWork(OVERSIZED, OVERSIZED_SIZE), "[]"));

      // then
      assertThat(java.rejections())
          .extracting(RejectionView::message)
          .containsExactly(TS_ATTACHMENT_TOO_LARGE);
    }
  }

  @Nested
  @DisplayName("받은 본문이 캡처 상한보다 크면")
  class OverCaptureLimit {
    @Test
    @DisplayName("run을 dependency_unavailable로 실패시킨다(TS와 같음)")
    void failsWhenBodyExceedsCaptureLimit() {
      // given
      jira.captureLimit(TINY_CAPTURE_LIMIT);

      // when
      Collection java =
          jira.collectFrom(JiraScenario.checkpointWith(attachmentWork(PNG, PNG_SIZE), "[]"));

      // then
      assertThat(java.failureKind()).contains(CallFailure.Kind.DEPENDENCY_UNAVAILABLE);
    }
  }

  @Nested
  @DisplayName("첨부 원본 요청이 실패로 답하면")
  class AttachmentFails {
    @Test
    @DisplayName("일시 장애(503)는 재시도해 원본을 받는다")
    void retriesTransientFailure() {
      // given
      jira.failNext(ATTACHMENT_PATH + PNG, 1, 503);

      // when
      Collection java =
          jira.collectFrom(JiraScenario.checkpointWith(attachmentWork(PNG, PNG_SIZE), "[]"));

      // then
      assertThat(blobOf(java).size()).isEqualTo(JiraFixtures.attachmentBytes(PNG).length);
    }

    @ParameterizedTest(name = "HTTP {0}")
    @ValueSource(ints = {403, 404})
    @DisplayName("권한이 없거나 사라진 첨부(403·404)는 거부로 남긴다")
    void rejectsUnavailableAttachment(int status) {
      // given
      jira.pathAnswers(ATTACHMENT_PATH + PNG, status);

      // when
      Collection java =
          jira.collectFrom(JiraScenario.checkpointWith(attachmentWork(PNG, PNG_SIZE), "[]"));

      // then
      assertThat(java.rejections())
          .extracting(RejectionView::message)
          .containsExactly(TS_UNAVAILABLE);
    }
  }

  @Nested
  @DisplayName("downloadAttachments:false인 소스에서 첨부가 있는 이슈를 수집하면")
  class DownloadsOff {
    @BeforeEach
    void givenDownloadsOff() {
      jira.attachmentDownloadsOff();
    }

    @Test
    @DisplayName("첨부 원본을 요청하지 않는다")
    void doesNotRequestAttachment() {
      // when
      jira.collectFrom(
          JiraScenario.checkpointWith("[{\"kind\":\"issue\",\"id\":\"40001\"}]", "[]"));

      // then: 첨부 50001을 가진 이슈 40001은 읽었다(아니면 given 준비 실패)
      jira.requireRequested(ISSUE_40001);
      assertThat(jira.requests())
          .extracting(RequestView::path)
          .noneMatch(path -> path.startsWith(ATTACHMENT_PATH));
    }
  }

  @Nested
  @DisplayName("다운로드 이력이 있는 체크포인트에서 수집하면")
  class WithDownloadHistory {
    @Test
    @DisplayName("받은 첨부 id를 기존 이력 뒤에 더한다")
    void appendsToDownloadHistory() {
      // when
      Collection java =
          jira.collectFrom(
              JiraScenario.checkpointWith(
                  attachmentWork(PNG, PNG_SIZE), "[\"" + EARLIER_DOWNLOAD + "\"]"));

      // then
      assertThat(java.downloadHistory()).containsExactly(EARLIER_DOWNLOAD, PNG);
    }

    @Test
    @DisplayName("이력에 있는 첨부는 이슈를 다시 읽어도 다시 받지 않는다")
    void skipsAlreadyDownloaded() {
      // when
      jira.collectFrom(
          JiraScenario.checkpointWith("[{\"kind\":\"issue\",\"id\":\"40001\"}]", "[\"50001\"]"));

      // then: 첨부 50001을 가진 이슈 40001은 읽었다(아니면 given 준비 실패)
      jira.requireRequested(ISSUE_40001);
      assertThat(jira.requests())
          .extracting(RequestView::path)
          .doesNotContain(ATTACHMENT_PATH + "50001");
    }
  }
}
