package io.scos.connector.notion.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.support.LogCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import tools.jackson.databind.ObjectMapper;

@Isolated
@DisplayName("부재 검증 보류는 운영자가 원인을 찾을 수 있는 이벤트를 남긴다")
class NotionAbsencesEventLogTest {
  static final ObjectMapper JSON = new ObjectMapper();
  static final String DEFERRED_EVENT =
      """
      {"level":"warn","event":"notion.absence.deferred",
       "sourceInstanceId":"notion-synthetic","detail":{"reason":"LIMIT_EXCEEDED"}}
      """;

  final NotionAbsenceScenario context = new NotionAbsenceScenario();
  final LogCapture log = LogCapture.of(NotionAbsences.class);

  @AfterEach
  void close() {
    log.close();
    context.close();
  }

  @Test
  @DisplayName("후보 한도 초과는 warn에 소스와 보류 사유를 남긴다")
  void logsCandidateLimitExceeded() {
    context.exceedsCandidateLimit();
    context.reconcile();
    // then
    assertThat(log.events("notion.absence.deferred"))
        .singleElement()
        .isEqualTo(JSON.readTree(DEFERRED_EVENT));
  }
}
