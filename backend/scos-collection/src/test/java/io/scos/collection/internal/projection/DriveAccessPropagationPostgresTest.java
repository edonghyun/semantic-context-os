package io.scos.collection.internal.projection;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.collection.internal.AccessResult;
import io.scos.collection.internal.projection.DriveAccessScenario.BodyState;
import io.scos.spi.access.DocumentAcl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("db")
@DisplayName("Drive 파일 권한은 본문 변경 없이 제한 엔진에 전파된다(T-b15)")
class DriveAccessPropagationPostgresTest {
  private DriveAccessScenario scenario;

  @BeforeEach
  void setUp() throws Exception {
    scenario = new DriveAccessScenario();
  }

  @AfterEach
  void close() {
    scenario.close();
  }

  @Test
  @DisplayName("드라이브 멤버라도 제한 폴더 아래 파일 permission에 없으면 검색할 수 없다")
  void restrictedChildDoesNotInheritDriveMembership() {
    // given
    scenario.givenRestrictedChild();
    // when
    var result = scenario.search("U1");
    // then
    assertThat(result.hits()).isEmpty();
  }

  @Test
  @DisplayName("폴더에서 상속한 도메인 공유가 해제되면 본문 변경 없이 투영을 요청하고 검색을 회수한다")
  void unchangedFolderDomainRevocationRequestsProjection() {
    // given
    scenario.givenDomainSharedChild();
    scenario.revokeFolderDomain();
    scenario.givenPendingProjection();
    long before = scenario.requestVersion();
    long changes = scenario.bodyChanges();
    // when
    var run = scenario.collect();
    // then
    assertThat(scenario.bodyState(run)).isEqualTo(new BodyState(0, 0, changes));
    assertThat(scenario.requestVersion()).isGreaterThan(before);
    scenario.project();
    assertThat(scenario.search("U1").hits()).isEmpty();
  }

  @Test
  @DisplayName("ACL만 새로 읽고 투영할 때 임베더를 부르지 않는다")
  void aclOnlyRefreshEmbedsNothing() {
    // given
    scenario.givenDomainSharedChild();
    scenario.revokeFolderDomain();
    // when
    scenario.collectAndProject();
    // then
    assertThat(scenario.fileAcl().keys())
        .extracting(Object::toString)
        .containsExactly("user:drive-a:u2@example.com");
    assertThat(scenario.embeddingCalls()).isZero();
  }

  @Test
  @DisplayName("권한 읽기 404는 본문을 active로 보존하고 FAILED ACL로 검색을 막는다")
  void readerFailureDoesNotDeleteBody() {
    // given
    scenario.givenDomainSharedChild();
    scenario.failPermissions();
    // when
    scenario.collectAndProject();
    // then
    assertThat(scenario.bodyPreserved()).isTrue();
    assertThat(scenario.fileAcl().status()).isEqualTo(DocumentAcl.Status.FAILED);
    assertThat(scenario.search("U2").hits()).isEmpty();
  }

  @Test
  @DisplayName("독자 403도 COMPLETE로 저장하며 해당 문서를 검색에서 숨긴다")
  void readerForbiddenIsCompleteAndInvisible() {
    scenario.givenDomainSharedChild();
    scenario.readerCannotReadPermissions();
    var run = scenario.collectAndProject();
    // then
    assertThat(run.accessResult()).isEqualTo(AccessResult.COMPLETE);
    assertThat(run.aclUnreadable()).containsExactly("file:F1");
    assertThat(scenario.search("U2").hits()).isEmpty();
  }

  @Test
  @DisplayName("Directory가 없으면 관리자가 연결한 신원도 group 권한으로 검색할 수 없다")
  void groupWithoutDirectoryIsInvisible() {
    // given
    scenario.givenGroupSharedChild();
    // when
    var result = scenario.search("U1");
    // then
    assertThat(scenario.fileAcl().keys())
        .extracting(Object::toString)
        .containsExactlyInAnyOrder(
            "group:drive-a:editors@example.com", "user:drive-a:u2@example.com");
    assertThat(result.hits()).isEmpty();
  }
}
