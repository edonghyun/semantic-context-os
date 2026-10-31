package io.scos.engine.support.call;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 공급자 호출 정책 팩토리(공개 배포 단계 5 T3): 대화형(검색)은 1회 시도·총 예산 = 시도 timeout, 일괄(수집·투영)은 3회·총 2분이다. 시도 timeout은
 * 역할마다 공급자 설정에서 온다. 재정렬이 꺼져 있으면 재정렬 정책은 없다.
 */
@DisplayName("공급자 호출 정책은 대화형이면 1회, 일괄이면 3회·2분으로 만든다")
class ProviderCallPoliciesTest {
  static final Duration EMBEDDING_TIMEOUT = Duration.ofSeconds(7);
  static final Duration RERANK_TIMEOUT = Duration.ofSeconds(11);
  static final int BATCH_ATTEMPTS = 3;
  static final Duration BATCH_BUDGET = Duration.ofMinutes(2);

  @Nested
  @DisplayName("대화형이면")
  class Interactive {
    final ProviderCallPolicies policies =
        ProviderCallPolicies.of(true, EMBEDDING_TIMEOUT, Optional.of(RERANK_TIMEOUT));

    @Test
    @DisplayName("임베딩은 1회 시도·총 예산 = 시도 timeout이다")
    void embeddingIsSingleAttempt() {
      // when·then
      assertThat(policies.embedding())
          .extracting(
              ProviderCallPolicy::requestTimeout,
              ProviderCallPolicy::maxAttempts,
              ProviderCallPolicy::maxElapsed)
          .containsExactly(EMBEDDING_TIMEOUT, 1, EMBEDDING_TIMEOUT);
    }

    @Test
    @DisplayName("재정렬은 자기 시도 timeout으로 1회 시도한다")
    void rerankUsesItsOwnTimeout() {
      // when·then
      assertThat(policies.rerank())
          .get()
          .extracting(
              ProviderCallPolicy::requestTimeout,
              ProviderCallPolicy::maxAttempts,
              ProviderCallPolicy::maxElapsed)
          .containsExactly(RERANK_TIMEOUT, 1, RERANK_TIMEOUT);
    }
  }

  @Test
  @DisplayName("일괄이면 두 역할 모두 시도 timeout 그대로 3회·총 2분이다")
  void batchRetries() {
    // when
    ProviderCallPolicies policies =
        ProviderCallPolicies.of(false, EMBEDDING_TIMEOUT, Optional.of(RERANK_TIMEOUT));

    // then
    assertThat(List.of(policies.embedding(), policies.rerank().orElseThrow()))
        .extracting(
            ProviderCallPolicy::requestTimeout,
            ProviderCallPolicy::maxAttempts,
            ProviderCallPolicy::maxElapsed)
        .containsExactly(
            tuple(EMBEDDING_TIMEOUT, BATCH_ATTEMPTS, BATCH_BUDGET),
            tuple(RERANK_TIMEOUT, BATCH_ATTEMPTS, BATCH_BUDGET));
  }

  @Test
  @DisplayName("재정렬 timeout이 없으면 재정렬 정책도 없다")
  void noRerankPolicyWithoutRerank() {
    // when·then
    assertThat(ProviderCallPolicies.of(true, EMBEDDING_TIMEOUT, Optional.empty()).rerank())
        .isEmpty();
  }
}
