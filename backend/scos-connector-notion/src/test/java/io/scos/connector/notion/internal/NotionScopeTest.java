package io.scos.connector.notion.internal;

import static io.scos.connector.notion.internal.NotionFixtures.CHILD_PAGE;
import static io.scos.connector.notion.internal.NotionFixtures.UNKNOWN_PAGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.spi.connector.CollectionState.BodySummary;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("roots 범위는 현재 부모 사슬로 판정한다")
class NotionScopeTest {
  final NotionScopeScenario context = new NotionScopeScenario();

  @AfterEach
  void close() {
    context.close();
  }

  @Test
  @DisplayName("workspace에 닿는 공유 페이지는 범위 밖이다")
  void excludesWorkspacePage() {
    // when
    var verdict = context.scope().check(context.workspacePage());
    // then
    assertThat(verdict).isEqualTo(NotionScope.Verdict.OUTSIDE);
  }

  @Test
  @DisplayName("설정한 루트 페이지 자신은 범위 안이다")
  void includesConfiguredRoot() {
    // given
    var root = NotionScopeScenario.page(NotionScopeScenario.ROOT, "workspace", "");
    // when
    var verdict = context.scope().check(root);
    // then
    assertThat(verdict).isEqualTo(NotionScope.Verdict.INCLUDED);
  }

  @Test
  @DisplayName("DB 행의 부모 사슬이 루트에 닿으면 포함한다")
  void includesRowThroughDataSourceAndDatabase() {
    // given
    var row = context.rowUnderRoot();
    // when
    var verdict = context.scope().check(row);
    // then
    assertThat(verdict).isEqualTo(NotionScope.Verdict.INCLUDED);
  }

  @Test
  @DisplayName("조상 404는 범위 미확정이다")
  void unreadableAncestorIsUnknown() {
    // given
    var orphan = context.orphanedPage();
    // when
    var verdict = context.scope().check(orphan);
    // then
    assertThat(verdict).isEqualTo(NotionScope.Verdict.UNKNOWN);
  }

  @Test
  @DisplayName("블록을 부모로 가진 페이지도 루트까지 검증한다")
  void followsBlockParent() {
    // given
    var child = context.pageUnderBlock();
    // when
    var verdict = context.scope().check(child);
    // then
    assertThat(verdict).isEqualTo(NotionScope.Verdict.INCLUDED);
  }

  @Test
  @DisplayName("부모 사슬이 순환하면 범위를 확정하지 않는다")
  void cyclicParentIsUnknown() {
    // given
    var child = context.cyclicPage();
    // when
    var verdict = context.scope().check(child);
    // then
    assertThat(verdict).isEqualTo(NotionScope.Verdict.UNKNOWN);
  }

  @Test
  @DisplayName("부모 사슬이 안전 깊이를 넘으면 범위를 확정하지 않는다")
  void excessiveDepthIsUnknown() {
    // given
    var child = context.deeplyNestedPage();
    // when
    var verdict = context.scope().check(child);
    // then
    assertThat(verdict).isEqualTo(NotionScope.Verdict.UNKNOWN);
  }

  @Test
  @DisplayName("같은 탐색에서 검증한 조상은 다시 조회하지 않는다")
  void reusesVerifiedAncestorInScan() {
    // given
    context.sharedAncestorIsUnderRoot();
    var scope = context.scope();
    scope.check(NotionScopeScenario.page("first", "page_id", "ancestor"));
    // when
    scope.check(NotionScopeScenario.page("second", "page_id", "ancestor"));
    // then
    assertThat(context.ancestorRequests()).isEqualTo(1);
  }

  @Test
  @DisplayName("루트 UUID의 하이픈 표기 차이는 같은 ID로 판정한다")
  void normalizesRootUuid() {
    // given
    var scope = context.scopeForRoot("01234567-89ab-cdef-0123-456789abcdef");
    var root = NotionScopeScenario.page("0123456789abcdef0123456789abcdef", "workspace", "");
    // when
    var verdict = scope.check(root);
    // then
    assertThat(verdict).isEqualTo(NotionScope.Verdict.INCLUDED);
  }

  @Test
  @DisplayName("검증 소속이 있는 휴지통 페이지는 부모를 못 읽어도 포함한다")
  void includesKnownTrashWithoutParent() {
    // given
    var trashed =
        NotionScopeScenario.page("trashed", "page_id", "unreadable").put("in_trash", true);
    var known = new BodySummary(true, Optional.empty(), true, true);
    // when
    var verdict = context.scope().check(trashed, Optional.of(known));
    // then
    assertThat(verdict).isEqualTo(NotionScope.Verdict.INCLUDED);
  }

  @Test
  @DisplayName("다른 ID의 조상 응답을 범위 증거로 사용하지 않는다")
  void rejectsWrongAncestorIdentity() {
    // given
    context.metadata(
        "/v1/pages/ancestor", NotionScopeScenario.page(NotionScopeScenario.ROOT, "workspace", ""));
    var child = NotionScopeScenario.page(CHILD_PAGE, "page_id", "ancestor");
    // when·then
    assertThatThrownBy(() -> context.scope().check(child))
        .isInstanceOf(NotionProtocolException.class);
  }

  @Test
  @DisplayName("다음 탐색은 범위 밖으로 이동한 조상을 다시 검증한다")
  void revalidatesMovedAncestorInNextScan() {
    // given
    var child = context.formerlyIncludedPage();
    // when
    var verdict = context.scope().check(child);
    // then
    assertThat(verdict).isEqualTo(NotionScope.Verdict.OUTSIDE);
  }

  @Test
  @DisplayName("처음 보는 휴지통의 부모가 미확정이면 포함하지 않는다")
  void unknownTrashRemainsUnknown() {
    // given
    var trash = context.unknownTrash();
    // when
    var verdict = context.scope().check(trash, Optional.empty());
    // then
    assertThat(verdict).isEqualTo(NotionScope.Verdict.UNKNOWN);
  }

  @Test
  @DisplayName("active 항목에는 이전 소속만으로 범위 판정을 우회하지 않는다")
  void activePageIgnoresPriorMembership() {
    // given
    var known = new BodySummary(true, Optional.empty(), false, true);
    // when
    var verdict = context.scope().check(context.workspacePage(), Optional.of(known));
    // then
    assertThat(verdict).isEqualTo(NotionScope.Verdict.OUTSIDE);
  }

  @Test
  @DisplayName("알 수 없는 부모 종류는 범위 미확정이다")
  void unknownParentRemainsUnknown() {
    // given
    var child = NotionScopeScenario.page(CHILD_PAGE, "unknown_parent", UNKNOWN_PAGE);
    // when
    var verdict = context.scope().check(child);
    // then
    assertThat(verdict).isEqualTo(NotionScope.Verdict.UNKNOWN);
  }

  @ParameterizedTest
  @ValueSource(strings = {"database", "data_source"})
  @DisplayName("다른 컨테이너의 과거 소속은 처음 보는 휴지통 컨테이너의 소속 증거가 아니다")
  void unrelatedHistoryDoesNotVerifyTrashedContainer(String kind) {
    var scope = context.scopeWithUnrelatedContainerHistory();
    var trash =
        NotionScopeScenario.object(kind, "unknown-container", "workspace", "")
            .put("in_trash", true);
    var verdict = scope.check(trash);
    // then
    assertThat(verdict).isEqualTo(NotionScope.Verdict.OUTSIDE);
  }

  @Test
  @DisplayName("안전 깊이를 한 단계만 넘는 부모 사슬도 소속을 확정하지 않는다")
  void oneStepBeyondDepthIsUnknown() {
    var page = context.pageJustBeyondParentDepth();
    var verdict = context.scope().check(page);
    // then
    assertThat(verdict).isEqualTo(NotionScope.Verdict.UNKNOWN);
  }
}
