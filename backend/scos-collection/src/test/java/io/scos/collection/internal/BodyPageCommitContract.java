package io.scos.collection.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("페이지 커밋은 본문 완료를 같은 경계에서 반영한다")
public abstract class BodyPageCommitContract {
  protected BodyCommitScenario context;

  protected abstract BodyCommitScenario newContext();

  @BeforeEach
  void prepare() {
    context = newContext();
  }

  @Test
  @DisplayName("만료 전에도 fencing token이 교체된 점유의 본문 커밋은 거부한다")
  void replacedClaimRejectsCompletion() {
    context.previousBodyHasRemovedBlock();
    context.claimWasReplacedBeforeExpiry();
    // when·then
    assertThatThrownBy(context::completeBody).isInstanceOf(ClaimLostException.class);
    assertThat(context.removedBlockIsActive()).isTrue();
  }

  @Test
  @DisplayName("페이지 데이터와 완료 버전을 함께 커밋한다")
  void commitsCompletion() {
    // when
    context.completeBody();
    // then
    assertThat(context.completedVersion()).isEqualTo("v1");
  }

  @Test
  @DisplayName("부분 부재 삭제도 커밋 결과의 삭제 수에 포함한다")
  void countsScopeAbsence() {
    // given
    context.previousBodyHasRemovedBlock();
    // when
    var result = context.completeBody();
    // then
    assertThat(result.deleted()).isEqualTo(1);
  }

  @Test
  @DisplayName("부분 부재 삭제만 있어도 투영을 요청한다")
  void requestsProjectionForScopeAbsence() {
    // given
    context.previousBodyHasRemovedBlock();
    // when
    context.completeBody();
    // then
    assertThat(context.projectionRequested()).isTrue();
  }

  @Test
  @DisplayName("만료된 점유는 본문 완료 커밋도 거부한다")
  void expiredLeaseRejectsCompletion() {
    // given
    context.previousBodyHasRemovedBlock();
    context.claimHasExpired();
    // when·then
    assertThatThrownBy(context::completeBody).isInstanceOf(ClaimLostException.class);
    assertThat(context.removedBlockIsActive()).isTrue();
  }

  @Test
  @DisplayName("안정된 열거를 저장한 실제 run을 본문 완료에 남긴다")
  void recordsCompletingRun() {
    // when
    context.completeBody();
    // then
    assertThat(context.completedRun()).contains(BodyCommitScenario.RUN);
  }
}
