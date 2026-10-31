package io.scos.mcp;

import static io.scos.mcp.McpSearchAssembly.EMBED;
import static io.scos.mcp.McpSearchAssembly.GENERATE;
import static org.assertj.core.api.Assertions.assertThat;

import io.scos.engine.support.call.ProviderCallPolicy;
import io.scos.support.FakeHttpServer;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * 검색(MCP) 경로의 공급자 호출은 한 번 시도한다(ADR-0020 §5): 재정렬이 429를 받으면 Retry-After를 기다리지 않고 바로 폴백한다. 질의 임베딩이
 * 429·5xx면 폴백 없이 "Gemini embeddings <상태>" 도구 오류(isError)로 끝난다. 수집·투영 명령의 재시도 정책은 그대로다. Gemini는 로컬
 * fake HTTP.
 */
@DisplayName("검색(MCP) 경로의 공급자 호출은 한 번만 시도한다(ADR-0020 §5)")
class SearchProviderCallsTest {
  /** MCP 정책 사례가 주는 Gemini 시도 timeout(기본 30초와 다른 값). */
  static final Duration GEMINI_TIMEOUT = Duration.ofSeconds(7);

  static final long RETRY_AFTER_SECONDS = 60;
  static final int BATCH_ATTEMPTS = 3;
  static final Duration BATCH_BUDGET = Duration.ofMinutes(2);

  McpSearchAssembly mcp;

  @BeforeEach
  void assemble() throws Exception {
    mcp = new McpSearchAssembly();
  }

  @AfterEach
  void close() {
    mcp.close();
  }

  @Nested
  @DisplayName("MCP에서 질의 임베딩이 429(Retry-After 60s)를 받으면")
  class QueryEmbeddingRateLimited {
    @BeforeEach
    void rateLimited() {
      mcp.gemini.on("POST", EMBED, r -> FakeHttpServer.rateLimited(RETRY_AFTER_SECONDS));
    }

    @Test
    @DisplayName("키워드 결과로 낮추지 않고 'Gemini embeddings 429' 도구 오류(isError)로 끝난다")
    void failsWithToolFailure() {
      // when
      Throwable failure = mcp.searchFailure();

      // then
      assertThat(failure)
          .isInstanceOf(McpTools.ToolFailure.class)
          .hasMessage("Gemini embeddings 429");
    }

    @Test
    @DisplayName("Retry-After를 기다려 다시 시도하지 않는다")
    void isNotRetried() {
      // when
      mcp.searchFailure();

      // then
      assertThat(mcp.embedCalls()).isEqualTo(1);
    }
  }

  @Nested
  @DisplayName("MCP에서 질의 임베딩이 503을 받으면")
  class QueryEmbeddingUnavailable {
    @BeforeEach
    void unavailable() {
      mcp.gemini.on("POST", EMBED, r -> FakeHttpServer.status(503, "{}"));
    }

    @Test
    @DisplayName("키워드 결과로 낮추지 않고 'Gemini embeddings 503' 도구 오류(isError)로 끝난다")
    void failsWithToolFailure() {
      // when
      Throwable failure = mcp.searchFailure();

      // then
      assertThat(failure)
          .isInstanceOf(McpTools.ToolFailure.class)
          .hasMessage("Gemini embeddings 503");
    }

    @Test
    @DisplayName("다시 시도하지 않는다")
    void isNotRetried() {
      // when
      mcp.searchFailure();

      // then
      assertThat(mcp.embedCalls()).isEqualTo(1);
    }
  }

  @Nested
  @DisplayName("MCP에서 재정렬이 429(Retry-After 60s)를 받으면")
  class RerankRateLimited {
    @BeforeEach
    void rateLimited() {
      mcp.embeddingsSucceed();
      mcp.gemini.on("POST", GENERATE, r -> FakeHttpServer.rateLimited(RETRY_AFTER_SECONDS));
    }

    @Test
    @DisplayName("재정렬 없이 결과를 돌려준다")
    void rerankRateLimitFallsBack() {
      // when
      JsonNode result = mcp.searchOnce();

      // then
      assertThat(result.get("hits").size()).isEqualTo(2);
    }

    @Test
    @DisplayName("다시 시도하지 않는다")
    void rerankRateLimitIsNotRetried() {
      // when
      mcp.searchOnce();

      // then
      assertThat(mcp.generateCalls()).isEqualTo(1);
    }
  }

  @Test
  @DisplayName("MCP의 Gemini 호출 정책은 1회 시도·총 예산 = 시도 timeout이다")
  void mcpPolicyIsSingleAttempt() {
    // given: 시도 timeout을 7초로 둔다
    String timeout = "scos.gemini.timeout=" + GEMINI_TIMEOUT.toSeconds() + "s";

    // when
    ProviderCallPolicy policy = mcp.embeddingPolicy("mcp", timeout);

    // then
    assertThat(policy)
        .extracting(ProviderCallPolicy::maxAttempts, ProviderCallPolicy::maxElapsed)
        .containsExactly(1, GEMINI_TIMEOUT);
  }

  @Test
  @DisplayName("수집·투영 명령(기본 프로필)의 Gemini 호출 정책은 재시도 3회·총 2분 그대로다")
  void commandPolicyRetries() {
    // when
    ProviderCallPolicy policy = mcp.embeddingPolicy("default");

    // then
    assertThat(policy)
        .extracting(ProviderCallPolicy::maxAttempts, ProviderCallPolicy::maxElapsed)
        .containsExactly(BATCH_ATTEMPTS, BATCH_BUDGET);
  }
}
