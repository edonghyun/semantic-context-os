package io.scos.spi.store;

import static io.scos.spi.store.ContextUnits.unit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 소유 포트와 유닛 쓰기는 같은 저장 상태를 사용한다. newOwnership()이 매 테스트 상태를 초기화한다. */
@DisplayName("문서는 첫 기록자의 공간과 출처만 소유한다(A2·A3)")
public abstract class DocumentOwnershipContract {
  protected static final SpaceId A = new SpaceId("a");
  protected static final SpaceId B = new SpaceId("b");
  protected DocumentOwnership ownership;

  protected abstract DocumentOwnership newOwnership();

  protected abstract UnitMaintenance maintenance();

  @BeforeEach
  void setUpOwnership() {
    ownership = newOwnership();
  }

  @Test
  @DisplayName("첫 기록자는 문서를 소유한다")
  void firstWriterOwnsDocument() {
    var claim = ownership.claim(A, "source-a", "doc-1");

    // then
    assertThat(claim).isEqualTo(DocumentOwnership.Claim.OWNED);
  }

  @Test
  @DisplayName("같은 공간과 출처는 다시 소유를 확인할 수 있다")
  void ownerMayClaimAgain() {
    ownership.claim(A, "source-a", "doc-1");

    var claim = ownership.claim(A, "source-a", "doc-1");

    // then
    assertThat(claim).isEqualTo(DocumentOwnership.Claim.OWNED);
  }

  @Test
  @DisplayName("다른 출처의 선점은 거절하고 원래 소유를 보존한다")
  void otherSourceCannotClaim() {
    ownership.claim(A, "source-a", "doc-1");

    var claim = ownership.claim(A, "source-b", "doc-1");

    // then
    assertThat(claim).isEqualTo(DocumentOwnership.Claim.OTHER_SOURCE);
    assertThat(ownership.listDocumentIds(A, "source-a")).containsExactly("doc-1");
    assertThat(ownership.listDocumentIds(A, "source-b")).isEmpty();
  }

  @Test
  @DisplayName("다른 공간의 선점은 문서와 소유 공간을 밝혀 거부한다")
  void otherSpaceIsRejected() {
    ownership.claim(A, "source-a", "doc-1");

    // then
    assertThatThrownBy(() -> ownership.claim(B, "source-b", "doc-1"))
        .isInstanceOf(SpaceOwnershipException.class)
        .hasMessageContaining("doc-1");
    assertThat(ownership.listDocumentIds(A, "source-a")).containsExactly("doc-1");
  }

  @Test
  @DisplayName("유닛 없이 선점한 문서도 소유 목록에 있다")
  void listsOwnershipWithoutUnits() {
    ownership.claim(A, "source-a", "doc-1");

    var documents = ownership.listDocumentIds(A, "source-a");

    // then
    assertThat(documents).containsExactly("doc-1");
  }

  @Test
  @DisplayName("소유 목록은 공간과 출처가 모두 일치하는 문서만 반환한다")
  void listsOnlyRequestedOwner() {
    ownership.claim(A, "source-a", "doc-1");
    ownership.claim(A, "source-b", "doc-2");
    ownership.claim(B, "source-a", "doc-3");

    var documents = ownership.listDocumentIds(A, "source-a");

    // then
    assertThat(documents).containsExactly("doc-1");
  }

  @Test
  @DisplayName("유닛 쓰기로 선점한 문서도 소유 포트가 보호한다")
  void unitsShareOwnership() {
    maintenance().upsert(A, List.of(unit("u1").inDocument("doc-1").source("source-a").build()));

    var claim = ownership.claim(A, "source-b", "doc-1");

    // then
    assertThat(claim).isEqualTo(DocumentOwnership.Claim.OTHER_SOURCE);
  }

  @Test
  @DisplayName("유닛 없는 선점을 다른 출처의 유닛 쓰기가 우회할 수 없다")
  void ownershipWithoutUnitsRejectsForeignUnits() {
    ownership.claim(A, "source-a", "doc-1");

    // then
    assertThatThrownBy(
            () ->
                maintenance()
                    .upsert(A, List.of(unit("u1").inDocument("doc-1").source("source-b").build())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("doc-1");
    assertThat(maintenance().listAll(A)).isEmpty();
  }

  @Test
  @DisplayName("출처 충돌 배치를 거부하면 앞선 새 문서의 소유도 남기지 않는다")
  void rejectedBatchLeavesNoOwnership() {
    ownership.claim(A, "source-a", "doc-1");

    // then
    assertThatThrownBy(
            () ->
                maintenance()
                    .upsert(
                        A,
                        List.of(
                            unit("new").inDocument("doc-0").source("source-b").build(),
                            unit("foreign").inDocument("doc-1").source("source-b").build())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("doc-1");
    assertThat(ownership.listDocumentIds(A, "source-b")).isEmpty();
  }

  @Test
  @DisplayName("한 새 문서의 출처가 섞인 배치를 거부하면 소유도 남기지 않는다")
  void mixedSourceBatchLeavesNoOwnership() {
    // then
    assertThatThrownBy(
            () ->
                maintenance()
                    .upsert(
                        A,
                        List.of(
                            unit("first").inDocument("doc-1").source("source-a").build(),
                            unit("second").inDocument("doc-1").source("source-b").build())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("doc-1");
    assertThat(ownership.listDocumentIds(A, "source-a")).isEmpty();
  }

  @Test
  @DisplayName("공간 충돌 배치를 거부하면 앞선 새 문서의 소유도 남기지 않는다")
  void rejectedSpaceBatchLeavesNoOwnership() {
    ownership.claim(A, "source-a", "doc-1");

    // then
    assertThatThrownBy(
            () ->
                maintenance()
                    .upsert(
                        B,
                        List.of(
                            unit("new").inDocument("doc-2").source("source-b").build(),
                            unit("foreign").inDocument("doc-1").source("source-b").build())))
        .isInstanceOf(SpaceOwnershipException.class)
        .hasMessageContaining("doc-1");
    assertThat(ownership.listDocumentIds(B, "source-b")).isEmpty();
  }

  @Test
  @DisplayName("공간 조회는 출처가 일치하는 실제 소유만 반환한다")
  void ownedSpaceIsSourceScoped() {
    ownership.claim(A, "source-a", "doc-1");
    // then
    assertThat(ownership.ownedSpace("source-a", "doc-1")).contains(A);
    assertThat(ownership.ownedSpace("source-b", "doc-1")).isEmpty();
  }

  @Test
  @DisplayName("없는 문서 공간 조회는 소유를 만들지 않는다")
  void spaceReadDoesNotClaim() {
    ownership.claim(A, "source-a", "existing");
    var space = ownership.ownedSpace("source-a", "absent");
    // then
    assertThat(space).isEmpty();
    assertThat(ownership.listDocumentIds(A, "source-a")).containsExactly("existing");
  }
}
