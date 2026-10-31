package io.scos.provider.openai;

import static io.scos.provider.openai.OpenAiCompatibleFakes.API_PATH;
import static io.scos.support.FakeHttpServer.inOrder;
import static io.scos.support.FakeHttpServer.status;
import static io.scos.support.ProviderCallFakes.executor;
import static io.scos.support.ProviderCallFakes.startHttp;
import static org.assertj.core.api.Assertions.assertThat;

import io.scos.spi.connector.CancellationToken;
import io.scos.spi.embedding.Embedder;
import io.scos.support.BackgroundThread;
import io.scos.support.Eventually;
import io.scos.support.FakeHttpServer;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 데몬 종료(호출 스레드 인터럽트)는 마지막 HTTP 상태의 실패가 아니다 — 투영이 429·5xx로 분류하지 않게 상태 없이 끝난다. */
@DisplayName("OpenAI 호환 임베딩 호출을 503 뒤 재시도 대기 중에 인터럽트하면(데몬 종료)")
class OpenAiCompatibleCancellationTest {
  static final String ENDPOINT = API_PATH + "/embeddings";
  static final int UNAVAILABLE = 503;
  static final Duration WAIT_LIMIT = Duration.ofSeconds(10);
  static final Duration LONG_BUDGET = Duration.ofMinutes(10);

  final FakeHttpServer http = startHttp();
  final AtomicReference<Throwable> thrown = new AtomicReference<>();
  BackgroundThread caller;

  /** 503 뒤 실제 대기(제품 조립과 같은 waiter: 토큰·인터럽트로 깨어남)에 들어간 호출. */
  @BeforeEach
  void givenCallWaitingToRetry() {
    http.on("POST", ENDPOINT, inOrder(status(UNAVAILABLE, "{}")));
    OpenAiCompatibleEmbedder waiting =
        new OpenAiCompatibleEmbedder(
            http.baseUrl() + API_PATH,
            Optional.of("test-key"),
            "nomic-embed-text",
            4,
            executor((d, c) -> c.awaitCancellation(d), LONG_BUDGET),
            OpenAiCompatibleEmbedderTest.JSON);
    caller =
        BackgroundThread.start(
            "embed-caller",
            () -> {
              try {
                waiting.embed(List.of("a"));
              } catch (RuntimeException e) {
                thrown.set(e);
              }
            });
    Eventually.await(
        "첫 요청 뒤 재시도 대기",
        () ->
            http.received().size() == 1
                && caller.isWaitingIn(CancellationToken.class, "awaitCancellation"),
        WAIT_LIMIT);
  }

  @AfterEach
  void stop() {
    http.close();
  }

  @Test
  @DisplayName("취소 메시지의 EmbedderException으로 끝난다")
  void endsCancelled() throws Exception {
    // when
    caller.interruptAndJoin(WAIT_LIMIT);

    // then
    assertThat(thrown.get())
        .isInstanceOf(Embedder.EmbedderException.class)
        .hasMessage("OpenAI-compatible embeddings request failed: cancelled");
  }

  @Test
  @DisplayName("취소는 상태 코드가 없다")
  void cancellationHasNoStatus() throws Exception {
    // when
    caller.interruptAndJoin(WAIT_LIMIT);

    // then
    assertThat(thrown.get())
        .isInstanceOfSatisfying(
            Embedder.EmbedderException.class, e -> assertThat(e.status()).isEmpty());
  }

  @Test
  @DisplayName("더 시도하지 않는다")
  void doesNotRetry() throws Exception {
    // when
    caller.interruptAndJoin(WAIT_LIMIT);

    // then
    assertThat(http.received()).hasSize(1);
  }
}
