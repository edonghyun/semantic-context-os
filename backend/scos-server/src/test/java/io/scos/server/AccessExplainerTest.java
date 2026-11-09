package io.scos.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.spi.access.AccessFailureReason;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("접근 설명은 실제 사용자 키와 저장된 문서 결정으로 판정한다(O-1·A-1)")
class AccessExplainerTest {
  private final AccessExplainerScenario scenario = new AccessExplainerScenario();

  @Test
  @DisplayName("같은 키가 있으면 사용자에게 보인다고 설명한다")
  void intersectingUserIsVisible() {
    var result = scenario.explain(Optional.of("reader")).orElseThrow().user().orElseThrow();
    // then
    assertThat(result.visible()).isTrue();
    assertThat(result.intersection())
        .extracting(key -> key.toString())
        .containsExactly("user:drive-a:reader@example.com");
  }

  @Test
  @DisplayName("키 교집합이 있어도 FAILED 문서는 보이지 않는다")
  void failedAclCannotGrantVisibility() {
    scenario.failedWithKeys();
    var result = scenario.explain(Optional.of("reader")).orElseThrow().user().orElseThrow();
    // then
    assertThat(result.visible()).isFalse();
  }

  @Test
  @DisplayName("다른 키만 있으면 교집합은 비고 보이지 않는다")
  void disjointKeysAreInvisible() {
    scenario.disjointKeys();
    var result = scenario.explain(Optional.of("reader")).orElseThrow().user().orElseThrow();
    // then
    assertThat(result.intersection()).isEmpty();
    assertThat(result.visible()).isFalse();
  }

  @Test
  @DisplayName("알 수 없는 사용자는 알려진 사용자로 보고하지 않는다")
  void unknownUserHasNoResolvedKeys() {
    var result = scenario.explain(Optional.of("missing")).orElseThrow().user().orElseThrow();
    // then
    assertThat(result.known()).isFalse();
    assertThat(result.keys()).isEmpty();
  }

  @Test
  @DisplayName("ACL이 없으면 연결된 사용자도 볼 수 없다")
  void missingAclIsInvisible() {
    scenario.missingAcl();
    var result = scenario.explain(Optional.of("reader")).orElseThrow().user().orElseThrow();
    // then
    assertThat(result.visible()).isFalse();
  }

  @Test
  @DisplayName("사용자 옵션을 생략하면 사용자 해석 결과를 만들지 않는다")
  void userMayBeOmitted() {
    var result = scenario.explain(Optional.empty()).orElseThrow();
    // then
    assertThat(result.user()).isEmpty();
  }

  @Test
  @DisplayName("누락된 접근 대상은 missing_target으로 설명한다")
  void diagnosisSeparatesMissingTargetFromExpiredAcl() {
    // given
    scenario.failedFor(AccessFailureReason.MISSING_TARGET);
    // when
    var result = scenario.explain(Optional.empty()).orElseThrow();
    // then
    assertThat(result.document().acl().orElseThrow().reason().wire()).isEqualTo("missing_target");
  }

  @Test
  @DisplayName("만료된 관측은 expired로 설명한다")
  void diagnosisPreservesExpiredReason() {
    // given
    scenario.failedFor(AccessFailureReason.EXPIRED);
    // when
    var result = scenario.explain(Optional.empty()).orElseThrow();
    // then
    assertThat(result.document().acl().orElseThrow().reason().wire()).isEqualTo("expired");
  }

  @Test
  @DisplayName("문서가 없으면 설명도 없다")
  void unknownDocumentHasNoExplanation() {
    scenario.unknownDocument();
    var result = scenario.explain(Optional.of("reader"));
    // then
    assertThat(result).isEmpty();
  }
}
