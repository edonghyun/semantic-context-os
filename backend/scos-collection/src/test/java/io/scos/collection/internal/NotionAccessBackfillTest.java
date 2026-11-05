package io.scos.collection.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Notion 보충 요청은 실제 메모리 유닛과 쓰기 경계를 따른다")
class NotionAccessBackfillTest {
  private final AccessCollectionScenario scenario = new AccessCollectionScenario("notion");

  @AfterEach
  void close() {
    scenario.close();
  }

  @Test
  @DisplayName("기존 유닛의 ACL 누락은 투영을 요청한다")
  void missingDocumentRequestsProjection() {
    scenario.existingDocument();
    scenario.collect();
    // then
    assertThat(scenario.memory.outbox().request(scenario.source.id())).isPresent();
  }

  @Test
  @DisplayName("실제 문서가 없으면 투영을 요청하지 않는다")
  void noDocumentDoesNotRequestProjection() {
    scenario.collect();
    // then
    assertThat(scenario.memory.outbox().request(scenario.source.id())).isEmpty();
  }

  @Test
  @DisplayName("누락 집계 중 취소되면 요청을 쓰지 않는다")
  void cancellationDuringCountWritesNothing() {
    scenario.existingDocument();
    scenario.afterMissingCount(scenario.cancel::cancel);
    scenario.collect();
    // then
    assertThat(scenario.memory.outbox().request(scenario.source.id())).isEmpty();
  }

  @Test
  @DisplayName("마감된 run은 보충을 요청할 수 없다")
  void finishedRunCannotRequestBackfill() {
    scenario.existingDocument();
    scenario.finishRun();
    scenario.collect();
    // then
    assertThat(scenario.memory.outbox().request(scenario.source.id())).isEmpty();
  }

  @Test
  @DisplayName("캡처한 소스 설정이 달라지면 요청하지 않는다")
  void changedSourceCannotRequestBackfill() {
    scenario.existingDocument();
    scenario.changeSourceConfiguration();
    scenario.collect();
    // then
    assertThat(scenario.memory.outbox().request(scenario.source.id())).isEmpty();
  }

  @Test
  @DisplayName("heartbeat 오류가 있으면 보충을 요청하지 않는다")
  void unhealthyHeartbeatCannotRequestBackfill() {
    scenario.existingDocument();
    scenario.heartbeatStorageFails();
    scenario.collect();
    // then
    assertThat(scenario.memory.outbox().request(scenario.source.id())).isEmpty();
  }
}
