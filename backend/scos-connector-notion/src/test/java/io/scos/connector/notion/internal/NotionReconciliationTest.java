package io.scos.connector.notion.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.CallFailureException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("전체 재조정은 같은 run에서 증분 탐색과 교대한다")
class NotionReconciliationTest {
  final NotionReconciliationScenario context = new NotionReconciliationScenario();

  @AfterEach
  void close() {
    context.close();
  }

  @Test
  @DisplayName("신규 DS의 전체 백필 뒤 catch-up이 조각 경계를 넘어도 전체를 완주한다")
  void fullCatchUpSurvivesNestedIncremental() {
    context.newDataSourceCatchUpCrossesSlice();
    context.collect();
    // then
    assertThat(context.fullTraversalFinished()).isTrue();
  }

  @Test
  @DisplayName("실제 전체 Driver는 DB와 DS metadata의 상태 발견을 container로 커밋한다")
  void recordsContainerStatesThroughDriver() {
    // given
    context.databaseInFullTree();
    // when
    context.collect();
    // then
    assertThat(context.containerDiscoveries())
        .containsExactlyInAnyOrder(
            "database:" + NotionDataSourcesScenario.DATABASE,
            "data-source:" + NotionDataSourcesScenario.DATA_SOURCE);
  }

  @Test
  @DisplayName("내부 증분 뒤 전체는 오래된 조상 memo로 범위 밖 행을 저장하지 않는다")
  void fullScopeIsRefreshedAfterIncremental() {
    // given
    context.databaseMovesOutsideDuringIncremental();
    // when
    context.collect();
    // then
    assertThat(context.laterRowWasStored()).isFalse();
  }

  @Test
  @DisplayName("전체 본문 도중 증분 시각이 되면 전체의 다음 응답보다 새 페이지를 먼저 완료한다")
  void incrementalRunsBeforeFullContinues() {
    // given
    context.changedPageAppearsDuringFullBody();
    // when
    context.collect();
    // then
    assertThat(context.changedBodyWasCompleteBeforeFullContinued).isTrue();
  }

  @Test
  @DisplayName("중간 전체 큐의 INCREMENTAL 재기동은 남은 자식부터 이어 간다")
  void resumesRemainingFullTraversal() {
    // given
    context.restartedAtChildPage();
    // when
    context.collect();
    // then
    assertThat(context.completedChild()).isTrue();
    assertThat(context.rootBodyRequests()).isZero();
  }

  @Test
  @DisplayName("증분 실패는 다음 내부 탐색 시각을 전진시키지 않는다")
  void failureDoesNotPostponeIncremental() {
    // given
    context.requiredDataSourceFails();
    // when·then
    assertThatThrownBy(context::collect).isInstanceOf(CallFailureException.class);
    assertThat(context.nextIncrementalAt()).isEqualTo(NotionIncrementalScenario.START);
  }

  @Test
  @DisplayName("전체와 증분 교대 뒤에도 같은 본문 걷기의 순환 커서를 거부한다")
  void interleavingPreservesCursorTrail() {
    // given
    context.cyclicFullCursorCrossesIncrementalBoundary();
    // when·then
    assertThatThrownBy(context::collect)
        .isInstanceOfSatisfying(
            CallFailureException.class,
            failure ->
                assertThat(failure.failure().kind())
                    .isEqualTo(CallFailure.Kind.INVALID_PROVIDER_RESPONSE));
    assertThat(context.activeSearchRequests()).isEqualTo(2);
  }

  @Test
  @DisplayName("하위 트리 수집이 끝나면 완료한 자식 인계를 확인점에 남기지 않는다")
  void acknowledgesEveryCollectedChildHandoff() {
    // given
    context.nestedPageTree();
    // when
    context.collect();
    // then
    assertThat(context.hasChildHandoffs()).isFalse();
  }
}
