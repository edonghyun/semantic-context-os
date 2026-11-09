package io.scos.spi.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("접근 정책은 문서를 제한하는 모든 carrier를 같은 묶음에 둔다")
class AccessPolicyTest {
  private final RawItem first = RawItems.item("first").build();
  private final RawItem second = RawItems.item("second").build();
  private final AccessPolicy policy =
      AccessPolicy.supported(Set.of("slack.message"), Set.of(), item -> Optional.of("document"));

  @ParameterizedTest
  @CsvSource({"-1,0,0", "0,-1,0", "0,0,-1"})
  @DisplayName("음수 대상과 호출과 경과 시간은 비용으로 만들 수 없다")
  void negativeCostIsRejected(long targets, long calls, long elapsed) {
    // when·then
    assertThatThrownBy(() -> new AccessCost(targets, OptionalLong.of(calls), elapsed))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Access cost must be non-negative");
  }

  @Test
  @DisplayName("공용 sink는 접근 페이지와 비용을 함께 기록한다")
  void recordedSinkPreservesPagesAndCost() {
    var sink = new RecordedAclSink();
    var page = new AclPage(Map.of(), List.of(), List.of());
    sink.accept(page);
    // when
    sink.cost(new AccessCost(0, OptionalLong.empty(), 0));
    // then
    assertThat(sink.pages()).containsExactly(new AclPage(Map.of(), List.of(), List.of()));
    assertThat(sink.costs()).containsExactly(new AccessCost(0, OptionalLong.empty(), 0));
  }

  @Test
  @DisplayName("지원 원천의 신원 목록은 기본적으로 부분 목록이다")
  void supportedDefaultsToPartialIdentityCoverage() {
    // then
    assertThat(policy.identityCoverage()).isEqualTo(IdentityCoverage.PARTIAL);
  }

  @Test
  @DisplayName("미지원 원천의 신원 목록은 부분 목록이다")
  void unsupportedDefaultsToPartialIdentityCoverage() {
    // when
    var coverage = AccessPolicy.unsupported().identityCoverage();
    // then
    assertThat(coverage).isEqualTo(IdentityCoverage.PARTIAL);
  }

  @Test
  @DisplayName("지정 원천의 신원 목록은 부분 목록이다")
  void scopedDefaultsToPartialIdentityCoverage() {
    // when
    var coverage = AccessPolicy.scoped("notion").identityCoverage();
    // then
    assertThat(coverage).isEqualTo(IdentityCoverage.PARTIAL);
  }

  @Test
  @DisplayName("전체 목록 선언은 기존 정책을 바꾸지 않는다")
  void identityCoverageDeclarationIsImmutable() {
    // when
    var full = policy.withIdentityCoverage(IdentityCoverage.FULL);
    // then
    assertThat(full.identityCoverage()).isEqualTo(IdentityCoverage.FULL);
    assertThat(policy.identityCoverage()).isEqualTo(IdentityCoverage.PARTIAL);
  }

  @Test
  @DisplayName("수집기를 연결해도 전체 목록 선언을 보존한다")
  void collectingPreservesIdentityCoverage() {
    // given
    var full = policy.withIdentityCoverage(IdentityCoverage.FULL);
    // when
    var collecting = full.collecting((input, sink) -> {});
    // then
    assertThat(collecting.identityCoverage()).isEqualTo(IdentityCoverage.FULL);
  }

  @Test
  @DisplayName("재관측 종류가 비면 carrier도 자동 재관측하지 않는다")
  void carrierDoesNotRefreshWithoutDeclaration() {
    // when
    boolean refresh = policy.refreshes(first);
    // then
    assertThat(refresh).isFalse();
  }

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
    assertThatThrownBy(() -> AccessPolicy.unsupported().collect(input, new RecordedAclSink()))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  @DisplayName("인스턴스 scope 선언도 직접 수집을 부르면 미구현으로 거부한다")
  void scopedDeclarationRejectsCollection() {
    var input = new AccessInputScenario().input(List.of());
    // when·then
    assertThatThrownBy(() -> AccessPolicy.scoped("notion").collect(input, new RecordedAclSink()))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
