package io.scos.collection.internal.projection;

import static io.scos.support.MeterRegistryAssert.assertThatMetrics;
import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.scos.collection.internal.OutboxPort;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.SourceInstanceId;
import io.scos.spi.embedding.Embedder;
import io.scos.spi.embedding.FixedEmbedder;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** 투영 취소(ADR-0020 §1): 종료로 멈춘 투영은 실패가 아니다 — 요청은 그대로 남아 다음 프로세스가 다시 투영한다. 메모리 포트. */
@DisplayName("종료로 멈춘 투영은 실패가 아니고 요청이 그대로 남는다(ADR-0020 §1)")
class ProjectionRunCancellationTest {
  /** 대역 임베더 차원(값은 결과에 영향 없음). */
  static final int EMBEDDING_DIM = 2;

  MemoryProjection fixture = new MemoryProjection();
  SimpleMeterRegistry registry = new SimpleMeterRegistry();
  SourceInstanceId source = fixture.slackSourceWithOneMessage("slack-a", "결제 장애 회고");

  /** 호출되면 before를 실행한 뒤 failure로 실패하는 임베더. */
  static Embedder failingEmbedder(Runnable before, Embedder.EmbedderException failure) {
    return new Embedder() {
      @Override
      public int dim() {
        return EMBEDDING_DIM;
      }

      @Override
      public List<double[]> embed(List<String> texts) {
        before.run();
        throw failure;
      }
    };
  }

  /** 공급자 503으로 실패하는 임베더. */
  static Embedder unavailableEmbedder() {
    return failingEmbedder(() -> {}, new Embedder.EmbedderException("Gemini embeddings 503", 503));
  }

  static CancellationToken cancelled() {
    CancellationToken token = new CancellationToken();
    token.cancel();
    return token;
  }

  List<ProjectionRun.Report> projectWith(Embedder embedder, CancellationToken token) {
    return fixture.projection(embedder, registry).runDue(token);
  }

  @Nested
  @DisplayName("이미 취소된 토큰으로 투영하면")
  class AlreadyCancelled {
    @Test
    @DisplayName("due 소스를 시작하지 않는다(보고 없음)")
    void startsNothing() {
      // when
      List<ProjectionRun.Report> reports =
          projectWith(FixedEmbedder.zero(EMBEDDING_DIM), cancelled());

      // then
      assertThat(reports).isEmpty();
    }

    @Test
    @DisplayName("유닛을 쓰지 않는다")
    void writesNoUnits() {
      // when
      projectWith(FixedEmbedder.zero(EMBEDDING_DIM), cancelled());

      // then
      assertThat(fixture.storedUnits()).isEmpty();
    }

    @Test
    @DisplayName("요청은 due로 남는다")
    void keepsRequestDue() {
      // when
      projectWith(FixedEmbedder.zero(EMBEDDING_DIM), cancelled());

      // then
      assertThat(fixture.dueNow()).containsExactly(source);
    }
  }

  @Nested
  @DisplayName("투영 중 종료로 취소되어 임베딩이 실패하면")
  class CancelledDuringEmbedding {
    CancellationToken cancel = new CancellationToken();

    /** 임베딩 도중 데몬 stop이 와서 토큰이 취소되고, 공급자 호출이 취소로 실패한다. */
    Embedder interruptedByShutdown =
        failingEmbedder(
            cancel::cancel,
            new Embedder.EmbedderException("Gemini embeddings request failed: cancelled"));

    @Test
    @DisplayName("run은 FAILED가 아니라 CANCELLED다")
    void outcomeIsCancelled() {
      // when
      List<ProjectionRun.Report> reports = projectWith(interruptedByShutdown, cancel);

      // then
      assertThat(reports)
          .extracting(ProjectionRun.Report::outcome)
          .containsExactly(ProjectionRun.Outcome.CANCELLED);
    }

    @Test
    @DisplayName("요청은 attempts·backoff 없이 남는다")
    void keepsRequestWithoutBackoff() {
      // when
      projectWith(interruptedByShutdown, cancel);

      // then
      assertThat(fixture.request(source))
          .get()
          .extracting(
              OutboxPort.Request::attempts,
              OutboxPort.Request::consecutiveFailures,
              OutboxPort.Request::nextAttemptAt)
          .containsExactly(0, 0, Optional.empty());
    }

    @Test
    @DisplayName("투영 점유는 TTL을 기다리지 않고 즉시 해제된다")
    void releasesClaimImmediately() {
      // when
      projectWith(interruptedByShutdown, cancel);

      // then
      assertThat(fixture.holdsActiveProjectionClaim(source)).isFalse();
    }

    @Test
    @DisplayName("run 타이머에 outcome=cancelled로 기록한다")
    void recordsCancelledRun() {
      // when
      projectWith(interruptedByShutdown, cancel);

      // then
      assertThatMetrics(registry)
          .hasTimerCount(ProjectionMetrics.RUN, Tags.of("outcome", "cancelled"), 1);
    }
  }

  @Nested
  @DisplayName("대조군: 취소 없이 임베딩이 503으로 실패하면")
  class FailureWithoutCancellation {
    @Test
    @DisplayName("투영은 FAILED다")
    void failureWithoutCancellationIsCounted() {
      // when
      List<ProjectionRun.Report> reports =
          projectWith(unavailableEmbedder(), CancellationToken.none());

      // then
      assertThat(reports)
          .extracting(ProjectionRun.Report::outcome)
          .containsExactly(ProjectionRun.Outcome.FAILED);
    }

    @Test
    @DisplayName("요청의 attempts가 1 오른다")
    void failureCountsAttempt() {
      // when
      projectWith(unavailableEmbedder(), CancellationToken.none());

      // then
      assertThat(fixture.request(source).orElseThrow().attempts()).isEqualTo(1);
    }
  }
}
