package io.scos.client;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.client.collect.CollectionReport;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("클라이언트 수집 실패는 원문 없는 운영 이벤트로 남긴다(LG-3·LG-5·LG-6)")
// 같은 제품 로거를 쓰는 다른 테스트의 이벤트가 섞이지 않게 한다.
@Isolated
class ClientCollectionEventLogTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @ParameterizedTest
  @ValueSource(strings = {"read", "clear"})
  @DisplayName("투영 중 state 실패는 문서 실패 대신 원천 실패로 보고한다")
  void stateFailureIsSourceFailure(String phase, @TempDir Path root) throws Exception {
    // given
    try (var scenario = new ClientCollectionScenario(root)) {
      scenario.givenStateProjectionFailure(phase);

      // when
      CollectionReport report = scenario.collect();

      // then
      assertThat(report)
          .extracting(CollectionReport::failedSources, CollectionReport::documentsFailed)
          .containsExactly(1, 0);
      assertThat(scenario.events("client.projection.failed")).isEmpty();
    }
  }

  @Test
  @DisplayName("원천 실패는 다음 주기에 재시도할 warn 이벤트로 남긴다")
  void sourceFailureIsWarn(@TempDir Path root) throws Exception {
    // given
    try (var scenario = new ClientCollectionScenario(root)) {
      scenario.givenUnreadableCheckpoints();

      // when
      scenario.collect();

      // then
      assertThat(scenario.events("client.source.failed"))
          .containsExactly(
              JSON.readTree(
                  """
              {"level":"warn","event":"client.source.failed",
               "sourceInstanceId":"claude-transcript",
               "detail":{"operation":"client.source.collect","errorType":"Failure"}}
              """),
              JSON.readTree(
                  """
              {"level":"warn","event":"client.source.failed",
               "sourceInstanceId":"claude-memory",
               "detail":{"operation":"client.source.collect","errorType":"Failure"}}
              """));
    }
  }

  @Test
  @DisplayName("묶음 투영 실패는 남은 문서 수와 함께 warn으로 남긴다")
  void batchFailureIsWarn(@TempDir Path root) throws Exception {
    // given
    try (var scenario = new ClientCollectionScenario(root)) {
      scenario.givenUnreadableIndexMetadata();

      // when
      scenario.collect();

      // then
      assertThat(scenario.events("client.projection.failed"))
          .containsExactly(
              JSON.readTree(
                  """
              {"level":"warn","event":"client.projection.failed",
               "sourceInstanceId":"claude-transcript",
               "detail":{"operation":"client.projection.drain","pendingDocuments":1,
                         "errorType":"UncategorizedSQLException"}}
              """));
    }
  }

  @Test
  @DisplayName("문서 격리는 원문이나 SQL 없이 문서 위치와 단계만 warn으로 남긴다")
  void isolatedDocumentIsWarn(@TempDir Path root) throws Exception {
    // given
    try (var scenario = new ClientCollectionScenario(root)) {
      scenario.givenRejectedDocumentWrites();

      // when
      scenario.collect();

      // then
      assertThat(scenario.events("client.document.failed"))
          .containsExactly(
              JSON.readTree(
                  """
              {"level":"warn","event":"client.document.failed",
               "sourceInstanceId":"claude-transcript",
               "detail":{"operation":"client.document.ingest",
                         "documentId":"transcript/d/s1","stage":"store"}}
              """));
    }
  }
}
