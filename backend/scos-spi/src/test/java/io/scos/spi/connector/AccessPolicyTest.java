package io.scos.spi.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("접근 정책은 문서를 제한하는 모든 carrier를 같은 묶음에 둔다")
class AccessPolicyTest {
  private final RawItem first = RawItems.item("first").build();
  private final RawItem second = RawItems.item("second").build();
  private final AccessPolicy policy =
      AccessPolicy.supported(Set.of("slack.message"), Set.of(), item -> Optional.of("document"));

  @Test
  @DisplayName("제한 carrier 누락은 해당 문서 위반이다")
  void omittedRestrictingCarrierInvalidatesDocument() {
    // when
    var invalid =
        policy.invalidDocuments(List.of(first, second), Map.of("document", List.of(first)));
    // then
    assertThat(invalid).containsExactly("document");
  }

  @Test
  @DisplayName("제한 carrier 분할은 두 문서 모두 위반이다")
  void splitRestrictingCarriersInvalidateBothDocuments() {
    // when
    var invalid =
        policy.invalidDocuments(
            List.of(first, second), Map.of("document", List.of(first), "other", List.of(second)));
    // then
    assertThat(invalid).containsExactlyInAnyOrder("document", "other");
  }

  @Test
  @DisplayName("묶음 전체 누락도 예상 문서 위반이다")
  void missingWholeBundleInvalidatesExpectedDocument() {
    // when
    var invalid = policy.invalidDocuments(List.of(first), Map.of());
    // then
    assertThat(invalid).containsExactly("document");
  }

  @Test
  @DisplayName("완전한 carrier 묶음은 순서와 관계없이 유효하다")
  void completeBundleIsValid() {
    // when
    var invalid =
        policy.invalidDocuments(List.of(first, second), Map.of("document", List.of(second, first)));
    // then
    assertThat(invalid).isEmpty();
  }

  @Test
  @DisplayName("다른 문서에 중복한 carrier는 그 문서 위반이다")
  void duplicateCarrierInAnotherDocumentInvalidatesThatDocument() {
    // when
    var invalid =
        policy.invalidDocuments(
            List.of(first), Map.of("document", List.of(first), "other", List.of(first)));
    // then
    assertThat(invalid).containsExactly("other");
  }

  @Test
  @DisplayName("재관측 종류는 반드시 carrier여야 한다")
  void refreshKindsMustBeCarriers() {
    // when·then
    assertThatThrownBy(() -> AccessPolicy.supported(Set.of(), Set.of("slack.message")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("미지원 선언도 직접 수집을 부르면 미구현으로 거부한다")
  void unsupportedDeclarationRejectsCollection() {
    var input = new AccessInputScenario().input(List.of());
    // when·then
    assertThatThrownBy(() -> AccessPolicy.unsupported().collect(input, page -> {}))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  @DisplayName("인스턴스 scope 선언도 직접 수집을 부르면 미구현으로 거부한다")
  void scopedDeclarationRejectsCollection() {
    var input = new AccessInputScenario().input(List.of());
    // when·then
    assertThatThrownBy(() -> AccessPolicy.scoped("notion").collect(input, page -> {}))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
