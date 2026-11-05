package io.scos.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("db")
@DisplayName("접근 explain은 문서 원인과 사용자 교집합을 읽기 전용으로 설명한다(O-1)")
class AccessExplainCliTest {
  private AccessExplainScenario scenario;

  @BeforeEach
  void setUp() {
    scenario = new AccessExplainScenario();
  }

  @Test
  @DisplayName("FAILED 문서의 원인과 결정 시각을 출력한다")
  void failedAclIncludesReasonAndDecisionTime() {
    var result = scenario.explain("source", "access", "explain", "file:F1");
    // then
    assertThat(result.path("acl").path("reason").asString()).isEqualTo("acl_unreadable");
    assertThat(result.path("acl").path("decidedAt").asString())
        .isEqualTo("2026-10-14T00:00:00.000Z");
  }

  @Test
  @DisplayName("사용자를 주면 저장소가 해석한 키와 문서 교집합을 출력한다")
  void resolvedKeysAndIntersectionComeFromIdentityStore() {
    scenario.allowedDocument();
    var result = scenario.explain("source", "access", "explain", "file:F1", "--user=reader");
    // then
    assertThat(result.path("user").path("keys").toString())
        .isEqualTo("[\"user:drive-a:reader@example.com\"]");
    assertThat(result.path("user").path("intersection").toString())
        .isEqualTo("[\"user:drive-a:reader@example.com\"]");
    assertThat(result.path("user").path("visible").asBoolean()).isTrue();
  }

  @Test
  @DisplayName("FAILED 문서는 연결된 사용자에게도 보이지 않는다고 설명한다")
  void failedAclRemainsInvisible() {
    var result = scenario.explain("source", "access", "explain", "file:F1", "--user=reader");
    // then
    assertThat(result.path("user").path("visible").asBoolean()).isFalse();
    assertThat(result.path("user").path("intersection").toString()).isEqualTo("[]");
  }

  @Test
  @DisplayName("공백으로 분리한 user 옵션도 해석한다")
  void userOptionAcceptsSeparatedValue() {
    scenario.allowedDocument();
    var result = scenario.explain("source", "access", "explain", "file:F1", "--user", "reader");
    // then
    assertThat(result.path("user").path("visible").asBoolean()).isTrue();
  }

  @Test
  @DisplayName("소유 문서에 ACL이 없으면 누락 원인을 출력한다")
  void missingAclIsExplicit() {
    scenario.missingAcl();
    var result = scenario.explain("source", "access", "explain", "file:F1");
    // then
    assertThat(result.path("acl").path("reason").asString()).isEqualTo("acl_missing");
  }

  @Test
  @DisplayName("알 수 없는 문서 조회는 null이고 저장하지 않는다")
  void unknownDocumentReturnsNull() {
    var result = scenario.explain("source", "access", "explain", "missing");
    // then
    assertThat(result.isNull()).isTrue();
  }

  @Test
  @DisplayName("carrier에도 FAILED 상태와 원천 실패 원인을 출력한다")
  void carrierFailureReasonIsIncluded() {
    var result = scenario.explain("source", "access", "explain", "file:F1");
    // then
    assertThat(result.path("carriers").get(0).path("reason").asString())
        .isEqualTo("acl_unreadable");
  }
}
