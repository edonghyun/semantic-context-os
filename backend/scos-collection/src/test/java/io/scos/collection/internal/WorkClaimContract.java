package io.scos.collection.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.scos.engine.support.text.MutableClock;
import io.scos.spi.connector.SourceInstanceId;
import io.scos.support.Race;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 작업 점유 포트 계약(불변식 5). 어댑터는 {@link #newPort()}만 제공한다. 같은 사례가 메모리·PostgreSQL에 적용된다. 동시성은 준비·출발
 * gate(Race)로 제어하며 sleep 동기화는 없다. 음성 사례는 원인 하나만 만든다(만료 / token 교체를 겹치지 않는다).
 */
@DisplayName("작업 점유는 소스마다 한 owner만 fencing token으로 가진다(불변식 5)")
public abstract class WorkClaimContract {
  protected static final SourceInstanceId SOURCE = new SourceInstanceId("src-a");
  protected static final OwnerId W1 = new OwnerId("w1");
  protected static final OwnerId W2 = new OwnerId("w2");
  protected static final Duration TTL = Duration.ofSeconds(30);
  static final int CONCURRENT_WORKERS = 8;
  static final Duration ONE_SECOND = Duration.ofSeconds(1);
  static final Duration RENEW_AFTER = Duration.ofSeconds(10);

  protected MutableClock clock;
  protected WorkClaimPort port;

  /** 비어 있는 저장소에 바인딩된 어댑터. */
  protected abstract WorkClaimPort newPort();

  @BeforeEach
  void setUpContract() {
    clock = new MutableClock(Instant.parse("2026-10-13T00:00:00Z"));
    port = newPort();
  }

  WorkClaim givenClaimedBy(OwnerId owner) {
    return port.tryClaim(SOURCE, owner, TTL, clock.instant())
        .orElseThrow(() -> new IllegalStateException("given 준비 실패: " + owner + "가 점유하지 못했다"));
  }

  /** 만료 1초 전에는 인계되지 않음을 확인한 뒤 시계를 만료 시각 정각으로 옮긴다. */
  void givenClockAtExpiryOf(WorkClaim claim) {
    clock.advance(TTL.minus(ONE_SECOND));
    Optional<WorkClaim> beforeExpiry = port.tryClaim(SOURCE, W2, TTL, clock.instant());
    if (beforeExpiry.isPresent()) throw new IllegalStateException("given 준비 실패: 만료 1초 전에 인계됐다");
    clock.advance(ONE_SECOND);
    if (!clock.instant().equals(claim.expiresAt()))
      throw new IllegalStateException("given 준비 실패: 시계가 만료 시각 정각이 아니다");
  }

  List<Optional<WorkClaim>> claimConcurrently() throws Exception {
    return Race.run(
        CONCURRENT_WORKERS, i -> port.tryClaim(SOURCE, new OwnerId("w" + i), TTL, clock.instant()));
  }

  @Nested
  @DisplayName("비어 있는 소스를 점유하면")
  class EmptySource {
    @Test
    @DisplayName("token 1로 점유한다")
    void claimsEmptySource() {
      // when
      Optional<WorkClaim> claim = port.tryClaim(SOURCE, W1, TTL, clock.instant());

      // then
      assertThat(claim).map(WorkClaim::fencingToken).contains(1L);
    }

    @Test
    @DisplayName("새 점유는 TTL 뒤에 만료된다")
    void newClaimExpiresAfterTtl() {
      // when
      WorkClaim claim = port.tryClaim(SOURCE, W1, TTL, clock.instant()).orElseThrow();

      // then
      assertThat(claim.expiresAt()).isEqualTo(clock.instant().plus(TTL));
    }

    @Test
    @DisplayName("새 점유가 현재 점유가 된다")
    void newClaimBecomesCurrent() {
      // when
      WorkClaim claim = port.tryClaim(SOURCE, W1, TTL, clock.instant()).orElseThrow();

      // then
      assertThat(port.current(SOURCE)).contains(claim);
    }
  }

  @Nested
  @DisplayName("W1의 점유가 유효한 상태에서")
  class ActiveClaim {
    WorkClaim first;

    @BeforeEach
    void givenClaimed() {
      first = givenClaimedBy(W1);
    }

    @Test
    @DisplayName("다른 owner의 점유는 실패한다")
    void rejectsSecondClaimWhileActive() {
      // when
      Optional<WorkClaim> second = port.tryClaim(SOURCE, W2, TTL, clock.instant());

      // then
      assertThat(second).isEmpty();
    }

    @Test
    @DisplayName("실패한 점유 시도는 현재 점유를 바꾸지 않는다")
    void rejectedClaimKeepsCurrent() {
      // when
      port.tryClaim(SOURCE, W2, TTL, clock.instant());

      // then
      assertThat(port.current(SOURCE)).contains(first);
    }

    @Test
    @DisplayName("보유자는 만료 전에 같은 token으로 연장한다")
    void holderRenews() {
      // given
      clock.advance(RENEW_AFTER);

      // when
      WorkClaim renewed = port.renew(first, TTL, clock.instant());

      // then
      assertThat(renewed)
          .extracting(WorkClaim::fencingToken, WorkClaim::expiresAt)
          .containsExactly(first.fencingToken(), clock.instant().plus(TTL));
    }

    @Test
    @DisplayName("해제하면 즉시 다른 owner가 점유할 수 있다")
    void releaseFreesSource() {
      // when
      port.release(first, clock.instant());

      // then
      Optional<WorkClaim> next = port.tryClaim(SOURCE, W2, TTL, clock.instant());
      assertThat(next).isPresent();
    }

    @Test
    @DisplayName("보유자가 아닌 해제는 무시된다")
    void releaseByOtherOwnerIsIgnored() {
      // given
      WorkClaim impostor = new WorkClaim(SOURCE, W2, first.fencingToken(), first.expiresAt());

      // when
      port.release(impostor, clock.instant());

      // then
      Optional<WorkClaim> next = port.tryClaim(SOURCE, W2, TTL, clock.instant());
      assertThat(next).isEmpty();
    }

    @Test
    @DisplayName("다른 소스의 점유는 서로 독립이다")
    void sourcesAreIndependent() {
      // when
      Optional<WorkClaim> b =
          port.tryClaim(new SourceInstanceId("src-b"), W2, TTL, clock.instant());

      // then
      assertThat(b).isPresent();
    }
  }

  @Nested
  @DisplayName("W1의 점유가 TTL을 지나 만료되면")
  class ExpiredClaim {
    WorkClaim first;

    @BeforeEach
    void givenExpired() {
      first = givenClaimedBy(W1);
      clock.advance(TTL.plusSeconds(1));
    }

    @Test
    @DisplayName("다른 owner가 token을 하나 올려 인계받는다")
    void takesOverExpiredClaim() {
      // when
      WorkClaim second = port.tryClaim(SOURCE, W2, TTL, clock.instant()).orElseThrow();

      // then
      assertThat(second)
          .extracting(WorkClaim::owner, WorkClaim::fencingToken)
          .containsExactly(W2, first.fencingToken() + 1);
    }

    @Test
    @DisplayName("만료된 점유는 연장할 수 없다")
    void renewFailsAfterExpiry() {
      // when·then
      assertThatThrownBy(() -> port.renew(first, TTL, clock.instant()))
          .isInstanceOf(ClaimLostException.class);
    }

    @Test
    @DisplayName("requireActive는 heartbeat가 멈춰도 저장 경계에서 거부한다")
    void requireActiveBlocksExpiredClaim() {
      // when·then
      assertThatThrownBy(() -> port.requireActive(first, clock.instant()))
          .isInstanceOf(ClaimLostException.class);
    }
  }

  @Nested
  @DisplayName("시계가 W1 점유의 만료 시각 정각(expiresAt == now)이면")
  class AtExpiryInstant {
    WorkClaim first;

    @BeforeEach
    void givenAtExpiry() {
      first = givenClaimedBy(W1);
      givenClockAtExpiryOf(first);
    }

    @Test
    @DisplayName("다른 owner가 인계받는다")
    void expiryInstantIsExclusive() {
      // when
      Optional<WorkClaim> atExpiry = port.tryClaim(SOURCE, W2, TTL, clock.instant());

      // then
      assertThat(atExpiry).isPresent();
    }

    @Test
    @DisplayName("점유는 비활성이다")
    void claimIsInactiveAtExpiryInstant() {
      // when
      boolean active = first.isActiveAt(clock.instant());

      // then
      assertThat(active).isFalse();
    }
  }

  @Nested
  @DisplayName("여러 worker가 동시에 점유를 시도하면")
  class ConcurrentClaims {
    @Test
    @DisplayName("정확히 하나만 성공한다")
    void onlyOneOfConcurrentClaimersWins() throws Exception {
      // when
      List<Optional<WorkClaim>> results = claimConcurrently();

      // then
      assertThat(results).filteredOn(Optional::isPresent).hasSize(1);
    }

    @Test
    @DisplayName("token은 한 번만 올라 1이다")
    void concurrentClaimsBumpTokenOnce() throws Exception {
      // when
      claimConcurrently();

      // then
      assertThat(port.current(SOURCE).orElseThrow().fencingToken()).isEqualTo(1L);
    }
  }

  @Nested
  @DisplayName("해제 뒤 같은 owner가 다시 점유해 옛 token이 시각상 아직 유효하면")
  class ReclaimedAfterRelease {
    WorkClaim old;
    WorkClaim current;

    @BeforeEach
    void givenReclaimedAfterRelease() {
      old = givenClaimedBy(W1);
      port.release(old, clock.instant());
      current = givenClaimedBy(W1);
      if (!old.isActiveAt(clock.instant()))
        throw new IllegalStateException("given 준비 실패: 옛 점유가 시각상 유효해야 한다");
    }

    @Test
    @DisplayName("옛 token의 연장은 거부된다")
    void renewRejectsSupersededTokenBeforeExpiry() {
      // when·then
      assertThatThrownBy(() -> port.renew(old, TTL, clock.instant()))
          .isInstanceOf(ClaimLostException.class);
    }

    @Test
    @DisplayName("옛 token의 연장은 거부되고, 현재 점유를 바꾸지 않는다")
    void rejectedRenewKeepsCurrent() {
      // when
      Throwable rejected = catchThrowable(() -> port.renew(old, TTL, clock.instant()));

      // then
      assertThat(rejected).isInstanceOf(ClaimLostException.class);
      assertThat(port.current(SOURCE)).contains(current);
    }

    @Test
    @DisplayName("옛 token의 쓰기는 거부된다")
    void requireActiveRejectsSupersededTokenBeforeExpiry() {
      // when·then
      assertThatThrownBy(() -> port.requireActive(old, clock.instant()))
          .isInstanceOf(ClaimLostException.class);
    }

    @Test
    @DisplayName("다시 점유한 현재 token의 쓰기는 통과한다")
    void requireActiveAcceptsCurrentToken() {
      // given
      WorkClaim stored = port.current(SOURCE).orElseThrow();

      // when·then
      assertThatCode(() -> port.requireActive(stored, clock.instant())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("늦게 끝난 옛 run이 옛 token으로 해제해도 새 점유를 풀지 않는다")
    void releaseWithSupersededTokenKeepsNewClaim() {
      // when
      port.release(old, clock.instant());

      // then
      Optional<WorkClaim> next = port.tryClaim(SOURCE, W2, TTL, clock.instant());
      assertThat(next).isEmpty();
    }
  }
}
