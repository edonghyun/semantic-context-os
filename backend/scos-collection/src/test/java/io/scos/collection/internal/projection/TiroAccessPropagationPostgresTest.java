package io.scos.collection.internal.projection;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.collection.internal.AccessResult;
import io.scos.collection.internal.CollectionRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Tiro 노트 권한은 접근 단계에서 관측해 제한 검색까지 전파된다(Tiro 접근 설계 §5·§6). 전원 공개 폴더 노트는 같은 조직 도메인 사용자에게, 그 밖의 노트는
 * 협업자(작성자)에게만 보이고, 폴더 목록 실패는 아무것도 열지 않는다.
 */
@Tag("db")
@DisplayName("Tiro 노트 권한은 폴더 공개와 협업자대로 검색에 전파된다")
class TiroAccessPropagationPostgresTest {
  private TiroAccessScenario scenario;

  @BeforeEach
  void setUp() {
    scenario = new TiroAccessScenario();
  }

  @AfterEach
  void close() {
    scenario.close();
  }

  @Test
  @DisplayName("같은 조직 사용자는 전원 공개 폴더 노트를 찾는다")
  void orgMemberSeesPublicFolderNote() {
    // given
    scenario.collectAndProject();

    // when
    var found = scenario.documentsFound("U2", "결제");

    // then
    assertThat(found).containsExactly("note:n-alpha");
  }

  @Test
  @DisplayName("같은 조직 사용자도 PRIVATE 폴더 노트는 찾지 못한다")
  void orgMemberDoesNotSeePrivateFolderNote() {
    // given
    scenario.collectAndProject();

    // when
    var found = scenario.documentsFound("U2", "온보딩");

    // then
    assertThat(found).isEmpty();
  }

  @Test
  @DisplayName("작성자는 자기 PRIVATE 폴더 노트를 찾는다")
  void authorSeesOwnPrivateNote() {
    // given
    scenario.collectAndProject();

    // when
    var found = scenario.documentsFound("U1", "온보딩");

    // then
    assertThat(found).containsExactly("note:n-beta");
  }

  @Test
  @DisplayName("폴더 목록이 정상이면 접근 단계는 COMPLETE다")
  void accessPhaseCompletes() {
    // when
    CollectionRun run = scenario.collectAndProject();

    // then
    assertThat(run.accessResult()).isEqualTo(AccessResult.COMPLETE);
  }

  @Test
  @DisplayName("폴더 목록이 실패하면 접근은 INCOMPLETE이고 공개 노트도 숨긴다")
  void folderFailureKeepsNotesHidden() {
    // given
    scenario.folderListingFails();

    // when
    CollectionRun run = scenario.collectAndProject();

    // then
    assertThat(run.accessResult()).isEqualTo(AccessResult.INCOMPLETE);
    assertThat(scenario.documentsFound("U2", "결제")).isEmpty();
  }
}
