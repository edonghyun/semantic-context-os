package io.scos.collection.internal.projection;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.collection.internal.AccessResult;
import io.scos.collection.internal.projection.JiraAccessScenario.BoundState;
import io.scos.collection.internal.projection.JiraAccessScenario.MergeWindow;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("db")
@DisplayName("Jira의 내용 기반 ACL은 그룹 병합 전에도 과거 멤버를 재사용하지 않는다")
class JiraAccessOrderingPostgresTest {
  private static final String NARROW =
      "~project:100:c28affbb6e9c6f5cd989cb8b8fb33b2e1c46459a65841b4fc786056f0d5c18c6";
  private static final String EMPTY =
      "~project:100:4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945";
  private JiraAccessScenario scenario;

  @BeforeEach
  void setUp() throws Exception {
    scenario = new JiraAccessScenario();
  }

  @AfterEach
  void close() {
    if (scenario != null) scenario.close();
  }

  @Test
  @DisplayName("접근 페이지와 문서 ACL은 디렉터리 병합 전에 관찰할 수 있다")
  void accessPageIsVisibleBeforeDirectoryMerge() throws Exception {
    // given
    scenario.givenVisibleIssue();
    scenario.roleUsers("100", "U2");
    // when
    try (var paused = scenario.pauseBeforeMerge()) {
      scenario.project();
      // then
      assertThat(paused.window(NARROW))
          .isEqualTo(new MergeWindow(NARROW, NARROW, true, Set.of("U1", "U2"), 0, true));
    }
  }

  static Stream<Arguments> boundSets() {
    return Stream.of(
        Arguments.of("COMPLETE", new BoundState(NARROW, Set.of("U2"), 1, AccessResult.COMPLETE)),
        Arguments.of("INCOMPLETE", new BoundState(NARROW, Set.of(), 0, AccessResult.INCOMPLETE)),
        Arguments.of("EXPLICIT_EMPTY", new BoundState(EMPTY, Set.of(), 1, AccessResult.COMPLETE)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("boundSets")
  @DisplayName("병합 모드별 hash 그룹은 바인딩된 사용자 집합 밖의 멤버를 저장하지 않는다")
  void sameBoundKeyNeverStoresOutsideUsers(String mode, BoundState expected) {
    // given
    scenario.givenVisibleIssue();
    scenario.prepareBoundSet(mode);
    // when
    scenario.collectAndProject();
    // then
    assertThat(scenario.boundState("10001")).isEqualTo(expected);
  }

  @Test
  @DisplayName("제한 해제와 Browse 축소가 겹쳐도 병합 전 과거 사용자는 열람할 수 없다")
  void companyRestrictionRemovalCannotUseStaleProjectMembers() throws Exception {
    // given
    scenario.givenRestrictedIssue("100");
    scenario.roleUsers("100", "U2");
    scenario.issueSecurity("10001", "");
    // when
    try (var paused = scenario.pauseBeforeMerge()) {
      paused.requireAclAheadOfMerge(NARROW);
      // then
      assertThat(scenario.readCount("U1", "10001")).isZero();
    }
  }

  @Test
  @DisplayName("관측 실패 뒤 좁은 Browse 복구는 병합 전 과거 사용자를 되살리지 않는다")
  void recoveryCannotUseStaleProjectMembers() throws Exception {
    // given
    scenario.givenDeniedIssue();
    scenario.roleUsers("100", "U2");
    // when
    try (var paused = scenario.pauseBeforeMerge()) {
      paused.requireAclAheadOfMerge(NARROW);
      // then
      assertThat(scenario.readCount("U1", "10001")).isZero();
    }
  }

  @Test
  @DisplayName("공유 수준 제거와 두 Browse 축소는 병합 전 과거 사용자를 허용하지 않는다")
  void companyLevelRemovalCannotUseStaleProjectMembers() throws Exception {
    // given
    scenario.givenRestrictedIssue("100", "200");
    scenario.roleUsers("100", "U2");
    scenario.roleUsers("200", "U2");
    scenario.issueSecurity("10001", "");
    // when
    try (var paused = scenario.pauseBeforeMerge()) {
      paused.requireAclAheadOfMerge(NARROW);
      // then
      assertThat(scenario.readCount("U1", "10001")).isZero();
    }
  }

  @Test
  @DisplayName("유형 상충 뒤 CM 복구는 병합 전 과거 CM 구성원을 재사용하지 않는다")
  void typeRecoveryCannotUseStaleCompanyMembers() throws Exception {
    // given
    scenario.givenUnknownIssue();
    scenario.companyProject("100");
    scenario.roleUsers("100", "U2");
    // when
    try (var paused = scenario.pauseBeforeMerge()) {
      paused.requireAclAheadOfMerge(NARROW);
      // then
      assertThat(scenario.readCount("U1", "10001")).isZero();
    }
  }
}
