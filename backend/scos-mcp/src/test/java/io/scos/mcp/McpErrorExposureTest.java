package io.scos.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.scos.spi.embedding.Embedder.EmbedderException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("다중 사용자 도구 실패는 원문을 가리고 참조만 응답한다(설계 §4.4)")
class McpErrorExposureTest {
  private static final RuntimeException DATABASE_FAILURE =
      new IllegalStateException("SELECT body FROM secret-doc");

  @Test
  @DisplayName("MASKED 인프라 예외는 원문 대신 참조가 있는 도구 오류를 낸다")
  void masksInfrastructureException() {
    // given
    var scenario = new McpFailureScenario(DATABASE_FAILURE);

    // when
    CallToolResult result = scenario.call(ErrorExposure.MASKED);

    // then
    assertThat(result.isError()).isTrue();
    assertThat(((TextContent) result.content().getFirst()).text())
        .matches("내부 오류가 발생했습니다\\(참조 [0-9a-f]{8}-[0-9a-f]{3}\\)");
  }

  @Test
  @DisplayName("MASKED 예외 로그는 도구 이름과 같은 참조 및 원문을 보존한다")
  void logsOriginalExceptionWithReference() {
    // given
    try (var scenario = new McpFailureLogScenario(DATABASE_FAILURE)) {
      // when
      CallToolResult result = scenario.call();

      // then
      var failures = scenario.failures();
      assertThat(failures)
          .singleElement()
          .extracting(
              e -> e.path("level").asString(),
              e -> e.at("/detail/tool").asString(),
              e -> e.at("/error/message").asString())
          .containsExactly("error", "search_context", "SELECT body FROM secret-doc");
      assertThat(((TextContent) result.content().getFirst()).text())
          .endsWith("참조 " + failures.getFirst().at("/detail/ref").asString() + ")");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"search_context", "assemble_context"})
  @DisplayName("MASKED 임베딩 공급자 실패는 내부 오류 문구로 응답한다")
  void masksEmbeddingFailure(String tool) {
    // given
    var scenario = new McpFailureScenario(new EmbedderException("Gemini embeddings 429", 429));

    // when
    CallToolResult result = scenario.call(tool, ErrorExposure.MASKED);

    // then
    assertThat(result.isError()).isTrue();
    assertThat(((TextContent) result.content().getFirst()).text())
        .matches("내부 오류가 발생했습니다\\(참조 [0-9a-f]{8}-[0-9a-f]{3}\\)");
  }

  @ParameterizedTest
  @ValueSource(strings = {"search_context", "assemble_context"})
  @DisplayName("MASKED 임베딩 공급자 실패는 거부 대신 error 이벤트를 남긴다")
  void logsEmbeddingFailureAsError(String tool) {
    // given
    try (var scenario =
        new McpFailureLogScenario(new EmbedderException("Gemini embeddings 503", 503))) {
      // when
      CallToolResult result = scenario.call(tool);

      // then
      var failures = scenario.failures();
      assertThat(failures)
          .singleElement()
          .extracting(
              e -> e.path("level").asString(),
              e -> e.at("/detail/tool").asString(),
              e -> e.at("/error/message").asString())
          .containsExactly("error", tool, "Gemini embeddings 503");
      assertThat(scenario.rejections()).isEmpty();
      assertThat(((TextContent) result.content().getFirst()).text())
          .endsWith("참조 " + failures.getFirst().at("/detail/ref").asString() + ")");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"search_context", "assemble_context"})
  @DisplayName("RAW 임베딩 공급자 실패는 기존 공급자 문구를 도구 오류로 응답한다")
  void rawPreservesEmbeddingFailure(String tool) {
    // given
    var scenario = new McpFailureScenario(new EmbedderException("Gemini embeddings 429", 429));

    // when
    CallToolResult result = scenario.call(tool, ErrorExposure.RAW);

    // then
    assertThat(result.isError()).isTrue();
    assertThat(((TextContent) result.content().getFirst()).text())
        .isEqualTo("Gemini embeddings 429");
  }

  @Test
  @DisplayName("MASKED Error는 원문을 suppressed로 보존하고 고정 문구로 다시 던진다")
  void masksFatalErrorWithoutCause() {
    // given
    var fatal = new StackOverflowError("secret-doc");
    var scenario = new McpFailureScenario(fatal);

    // when
    Throwable thrown = catchThrowable(() -> scenario.call(ErrorExposure.MASKED));

    // then
    assertThat(thrown)
        .isInstanceOf(Error.class)
        .hasNoCause()
        .hasMessageMatching("내부 오류가 발생했습니다\\(참조 [0-9a-f]{8}-[0-9a-f]{3}\\)");
    assertThat(thrown.getSuppressed()).containsExactly(fatal);
  }

  @Test
  @DisplayName("RAW 인프라 예외는 기존처럼 같은 예외를 전파한다")
  void rawPreservesException() {
    // given
    var scenario = new McpFailureScenario(DATABASE_FAILURE);

    // when·then
    assertThatThrownBy(() -> scenario.call(ErrorExposure.RAW)).isSameAs(DATABASE_FAILURE);
  }

  @ParameterizedTest
  @ValueSource(strings = {"read_document", "search_context"})
  @DisplayName("MASKED 계약 오류도 원문 대신 참조가 있는 고정 문구로 응답한다")
  void masksContractFailure(String tool) {
    // given
    var scenario = McpFailureScenario.healthy();

    // when
    CallToolResult result = scenario.contractFailure(tool);

    // then
    assertThat(result.isError()).isTrue();
    assertThat(((TextContent) result.content().getFirst()).text())
        .matches("요청을 처리할 수 없습니다\\(참조 [0-9a-f]{8}-[0-9a-f]{3}\\)");
  }

  @ParameterizedTest
  @ValueSource(strings = {"read_document", "search_context"})
  @DisplayName("MASKED 계약 오류는 error 대신 참조가 일치하는 warn 거부 이벤트를 남긴다")
  void logsContractRejectionAsWarning(String tool) {
    // given
    try (var scenario = McpFailureLogScenario.healthy()) {
      // when
      CallToolResult result = scenario.contractFailure(tool);

      // then
      var rejections = scenario.rejections();
      assertThat(rejections)
          .singleElement()
          .extracting(e -> e.path("level").asString(), e -> e.at("/detail/tool").asString())
          .containsExactly("warn", tool);
      assertThat(scenario.failures()).isEmpty();
      assertThat(((TextContent) result.content().getFirst()).text())
          .endsWith("참조 " + rejections.getFirst().at("/detail/ref").asString() + ")");
    }
  }

  @Test
  @DisplayName("MASKED Error는 다시 던지기 전에 원본 오류를 기록한다")
  void logsOriginalError() {
    // given
    try (var scenario = new McpFailureLogScenario(new StackOverflowError("secret-doc"))) {
      // when
      Throwable thrown = scenario.fail();

      // then
      var failures = scenario.failures();
      assertThat(failures)
          .singleElement()
          .extracting(e -> e.at("/error/message").asString())
          .isEqualTo("secret-doc");
      assertThat(thrown)
          .hasMessageEndingWith("참조 " + failures.getFirst().at("/detail/ref").asString() + ")");
    }
  }

  @Test
  @DisplayName("RAW Error는 원래 종류와 메시지를 보존해 전파한다")
  void rawPreservesError() {
    // given
    var fatal = new StackOverflowError("secret-doc");
    var scenario = new McpFailureScenario(fatal);

    // when·then
    assertThatThrownBy(() -> scenario.call(ErrorExposure.RAW)).isSameAs(fatal);
  }
}
