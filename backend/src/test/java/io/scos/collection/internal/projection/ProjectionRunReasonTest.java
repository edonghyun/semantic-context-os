package io.scos.collection.internal.projection;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.spi.embedding.Embedder;
import java.net.http.HttpTimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.DataAccessResourceFailureException;

/** 문서 실패 사유는 예외 타입·상태 코드로만 분류한다(DESIGN-PATTERNS §13) — 메시지 문구는 보지 않는다. */
@DisplayName("문서 실패 사유는 예외 타입·상태 코드로만 분류한다(DESIGN-PATTERNS §13)")
class ProjectionRunReasonTest {
  @ParameterizedTest(name = "{0} → {1}")
  @CsvSource({"429, rate_limited", "503, provider_unavailable", "400, unclassified"})
  @DisplayName("임베더 오류는 상태 코드로 분류한다")
  void embedderStatus(int status, String reason) {
    // when·then
    assertThat(ProjectionRun.reason(new Embedder.EmbedderException("x", status))).isEqualTo(reason);
  }

  @Test
  @DisplayName("상태 없는 임베더 오류는 메시지에 429가 있어도 unclassified다")
  void embedderMessageIsNotEvidence() {
    // when·then
    assertThat(
            ProjectionRun.reason(
                new Embedder.EmbedderException("Gemini embeddings 429 in message only")))
        .isEqualTo("unclassified");
  }

  @Test
  @DisplayName("원인 체인의 HttpTimeoutException은 timeout이다")
  void timeoutInCauseChain() {
    // when·then
    assertThat(
            ProjectionRun.reason(
                new Embedder.EmbedderException("request failed", new HttpTimeoutException("slow"))))
        .isEqualTo("timeout");
  }

  @Test
  @DisplayName("DataAccessException은 storage다")
  void dataAccessIsStorage() {
    // when·then
    assertThat(ProjectionRun.reason(new DataAccessResourceFailureException("db down")))
        .isEqualTo("storage");
  }

  @Test
  @DisplayName("메시지에만 timed out이 있는 예외는 unclassified다")
  void timeoutMessageIsNotEvidence() {
    // when·then
    assertThat(ProjectionRun.reason(new IllegalStateException("timed out (message only)")))
        .isEqualTo("unclassified");
  }
}
