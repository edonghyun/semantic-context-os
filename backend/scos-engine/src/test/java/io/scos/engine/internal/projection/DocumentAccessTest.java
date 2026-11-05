package io.scos.engine.internal.projection;

import static io.scos.engine.internal.projection.DocumentAccessScenario.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.engine.EmbeddingGenerationMismatchException;
import io.scos.engine.support.projection.WriteGuardException;
import io.scos.spi.access.DocumentAcl;
import io.scos.spi.access.DocumentAcl.Status;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("문서 ACL 선행 저장은 소유 충돌을 문서마다 격리한다")
class DocumentAccessTest {
  final DocumentAccessScenario scenario = new DocumentAccessScenario();

  @ParameterizedTest
  @CsvSource({"0,0", "500,1", "501,2"})
  @DisplayName("ACL 배치는 빈 마지막 트랜잭션 없이 500개 이하로 나뉜다")
  void aclBatchRequestsAreBounded(int documents, int expected) {
    scenario.decideAllowedBatch(documents);
    // then
    assertThat(scenario.transactionRequests()).isEqualTo(expected);
  }

  @Test
  @DisplayName("변경 없는 재투영은 유닛 쓰기가 불가능해도 저장 오류를 내지 않는다")
  void unchangedUnitsNeedNoWritePermission() {
    scenario.givenProjectedUnits();
    var report = scenario.repeatWithUnavailableUnitWrites();
    // then
    assertThat(report.errors()).isEmpty();
  }

  @Test
  @DisplayName("다른 출처의 인덱스가 소유 문서를 가리켜도 ACL을 지우지 않는다")
  void staleForeignIndexCannotPruneOwnerAcl() {
    scenario.givenOwnedDocument();
    scenario.pruneForeignIndexCandidate();
    // then
    assertThat(scenario.acl()).isEqualTo(new DocumentAcl("doc-01", Status.OK, Set.of(OWNER), T0));
  }

  @Test
  @DisplayName("열 건 중 공간 소유 충돌 한 건을 제외한 아홉 건의 ACL을 저장한다")
  void foreignSpaceSkipsOnlyConflictingAcl() {
    scenario.foreignSpaceOwnsOneDocument();
    // when
    scenario.syncTenDocuments();
    // then
    assertThat(scenario.aclIds())
        .containsExactlyInAnyOrder(
            "doc-01", "doc-02", "doc-03", "doc-04", "doc-06", "doc-07", "doc-08", "doc-09",
            "doc-10");
  }

  @Test
  @DisplayName("공간 충돌 문서 뒤에도 나머지 아홉 문서의 유닛을 저장한다")
  void foreignSpaceSkipsOnlyConflictingUnits() {
    scenario.foreignSpaceOwnsOneDocument();
    // when
    scenario.syncTenDocuments();
    // then
    assertThat(scenario.storedDocuments())
        .containsExactlyInAnyOrder(
            "doc-01", "doc-02", "doc-03", "doc-04", "doc-06", "doc-07", "doc-08", "doc-09",
            "doc-10");
  }

  @Test
  @DisplayName("공간 소유 충돌은 건너뛴 문서로 보고한다")
  void foreignSpaceIsReportedAsOwnershipSkipped() {
    scenario.foreignSpaceOwnsOneDocument();
    // when
    var report = scenario.syncTenDocuments();
    // then
    assertThat(report.ownershipSkipped()).containsExactly("doc-05");
  }

  @Test
  @DisplayName("같은 Clock에서도 새 결정 시각은 이전보다 1마이크로초 늦다")
  void equalClockAdvancesDecisionByOneMicrosecond() {
    scenario.givenOwnedDocument();
    // when
    scenario.restrictAtSameClock();
    // then
    assertThat(scenario.acl())
        .isEqualTo(new DocumentAcl("doc-01", Status.FAILED, Set.of(), T0.plusNanos(1000)));
  }

  @Test
  @DisplayName("임베딩 세대 거부 전에 ACL 회수를 커밋한다")
  void pendingGenerationStillCommitsAclRestriction() {
    scenario.givenOwnedDocument();
    scenario.pendingGeneration();
    // when·then
    assertThatThrownBy(scenario::restrictAtSameClock)
        .isInstanceOf(EmbeddingGenerationMismatchException.class);
    assertThat(scenario.acl().status()).isEqualTo(Status.FAILED);
  }

  @Test
  @DisplayName("다른 출처의 더 늦은 OK ACL도 소유자의 ACL을 바꾸지 못한다")
  void foreignSourceCannotReplaceOwnerAcl() {
    scenario.givenOwnedDocument();
    // when
    scenario.syncForeignDocument();
    // then
    assertThat(scenario.acl()).isEqualTo(new DocumentAcl("doc-01", Status.OK, Set.of(OWNER), T0));
  }

  @Test
  @DisplayName("prune은 유닛과 함께 기존 벡터를 삭제한다")
  void pruneRemovesStoredVectors() {
    scenario.givenProjectedUnits();
    scenario.clearDocuments();
    // then
    assertThat(scenario.storedVectorsForPreviousUnits()).isEmpty();
  }

  @Test
  @DisplayName("ACL만 남은 소유 문서도 prune에서 ACL을 삭제한다")
  void pruneRemovesAclOnlyDocument() {
    scenario.givenOwnedDocument();
    scenario.clearDocuments();
    // then
    assertThat(scenario.acl()).isNull();
  }

  @Test
  @DisplayName("prune 직전 점유 거부는 ACL 삭제 전에 전파된다")
  void pruneRequiresWriteGuard() {
    scenario.givenOwnedDocument();
    // when·then
    assertThatThrownBy(scenario::pruneWithRejectedGuard).isInstanceOf(WriteGuardException.class);
  }

  @Test
  @DisplayName("prune 직전 세대가 바뀌면 삭제를 거부한다")
  void pruneRechecksGeneration() {
    scenario.givenOwnedDocument();
    // when·then
    assertThatThrownBy(scenario::pruneAcrossGenerationChange)
        .isInstanceOf(EmbeddingGenerationMismatchException.class);
  }

  @Test
  @DisplayName("같은 유닛의 재투영은 임베딩을 다시 만들지 않는다")
  void unchangedUnitsDoNotEmbedAgain() {
    scenario.givenProjectedUnits();
    var report = scenario.syncTenDocuments();
    // then
    assertThat(report.embeddings().embedded()).isZero();
  }

  @Test
  @DisplayName("키워드 전용 유닛도 같은 본문을 오류 없이 재투영한다")
  void keywordOnlyUnitCanBeProjectedAgain() {
    scenario.givenKeywordDocument();
    var report = scenario.repeatKeywordDocument();
    // then
    assertThat(report.errors()).isEmpty();
  }

  @Test
  @DisplayName("키워드 전용 본문 재작성은 같은 유닛의 낡은 벡터를 지운다")
  void keywordRewriteRemovesStaleVector() {
    scenario.givenKeywordDocumentWithStaleVector();
    scenario.rewriteKeywordDocument();
    // then
    assertThat(scenario.storedVectorsForPreviousUnits()).isEmpty();
  }
}
