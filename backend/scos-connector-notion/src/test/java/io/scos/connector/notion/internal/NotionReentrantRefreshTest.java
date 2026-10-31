package io.scos.connector.notion.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("전체 본문 커밋 경계의 증분은 진행 중 읽기와 협력한다")
class NotionReentrantRefreshTest {
  final NotionReentrantRefreshScenario context = new NotionReentrantRefreshScenario();

  @AfterEach
  void close() {
    context.close();
  }

  @Test
  @DisplayName("같은 버전의 증분은 전체 본문의 남은 커서부터 이어 읽는다")
  void incrementalContinuesActiveFullBody() {
    // given
    context.pagedBodyInterruptedByIncremental();
    // when
    context.collectFull();
    // then
    assertThat(context.bodyRequests()).isEqualTo(2);
    assertThat(context.completedBlocks()).containsExactlyInAnyOrder("block:one", "block:two");
  }

  @Test
  @DisplayName("증분이 새 버전을 완료하면 중단했던 옛 본문을 다시 진행하지 않는다")
  void newerIncrementalBodySupersedesOldRead() {
    // given
    context.pageChangesAtIncrementalBoundary();
    // when
    context.collectFull();
    // then
    assertThat(context.bodyRequests()).isEqualTo(2);
    assertThat(context.completedBlocks()).containsExactly("block:kept");
  }

  @Test
  @DisplayName("전체 완료 커밋 경계의 증분은 같은 본문 완료를 다시 발행하지 않는다")
  void incrementalAtCompletionDoesNotFinishTwice() {
    // given
    context.incrementalAtBodyCompletion();
    // when
    context.collectFull();
    // then
    assertThat(context.completionFrames()).isEqualTo(1);
  }

  @ParameterizedTest
  @MethodSource("completedVersions")
  @DisplayName("중첩 증분이 본문을 끝내도 전체 호출은 완료 버전을 반환한다")
  void fullCallerReceivesCompletedVersion(
      NotionReentrantRefreshScenario.Boundary boundary, String version) {
    context.interruptAt(boundary);
    context.collectFull();
    // then
    assertThat(context.fullVersion()).contains(version);
  }

  @ParameterizedTest
  @MethodSource("completedVersions")
  @DisplayName("전체 읽기에 합류한 증분 호출도 완료 버전을 반환한다")
  void incrementalCallerReceivesCompletedVersion(
      NotionReentrantRefreshScenario.Boundary boundary, String version) {
    context.interruptAt(boundary);
    context.collectFull();
    // then
    assertThat(context.incrementalVersion()).contains(version);
  }

  @Test
  @DisplayName("중첩 증분의 남은 본문이 거부되면 전체 호출도 거부 미완료를 반환한다")
  void fullCallerReceivesNestedBodyRejection() {
    context.rejectedTailAtIncrementalBoundary();
    context.collectFull();
    // then
    assertThat(context.fullIncompletion()).contains(NotionPageRefresh.Incompletion.REJECTED);
  }

  @Test
  @DisplayName("진행 중 전체 본문에 합류한 증분도 남은 본문 거부를 반환한다")
  void incrementalCallerReceivesBodyRejection() {
    context.rejectedTailAtIncrementalBoundary();
    context.collectFull();
    // then
    assertThat(context.incrementalIncompletion()).contains(NotionPageRefresh.Incompletion.REJECTED);
  }

  @Test
  @DisplayName("읽기가 연속 교체돼도 이동한 옛 부모로 최신 완료 버전을 덮지 않는다")
  void successorChainPreservesLatestVersionAfterOldParentMoves() {
    context.pageChangesAcrossSuccessorChain();
    context.collectFull();
    // then
    assertThat(context.fullVersion()).contains(NotionReentrantRefreshScenario.FINAL_VERSION);
  }

  static Stream<Arguments> completedVersions() {
    return Stream.of(
        Arguments.of(
            NotionReentrantRefreshScenario.Boundary.NEW_VERSION_LAST_PAGE,
            NotionRefreshScenario.LATEST_VERSION),
        Arguments.of(
            NotionReentrantRefreshScenario.Boundary.BODY_PAGE, NotionRefreshScenario.VERSION),
        Arguments.of(
            NotionReentrantRefreshScenario.Boundary.NEW_VERSION,
            NotionRefreshScenario.LATEST_VERSION),
        Arguments.of(
            NotionReentrantRefreshScenario.Boundary.COMPLETION, NotionRefreshScenario.VERSION));
  }
}
