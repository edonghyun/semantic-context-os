package io.scos.connector.jira.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.scos.connector.jira.internal.JiraAccessDirectory.Decision;
import io.scos.connector.jira.internal.JiraAccessDirectory.IssueGrant;
import io.scos.connector.jira.internal.JiraAccessDirectory.ProjectAccess;
import io.scos.connector.jira.internal.JiraAccessDirectory.ProjectStyle;
import io.scos.connector.jira.internal.JiraGrantGroups.Grant;
import io.scos.spi.connector.AclPage;
import io.scos.spi.connector.CallFailure;
import io.scos.spi.source.SourceInstanceId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("Jira 프로젝트 결과는 판정과 grant·실패 상태의 모순을 거부한다 (설계 §4.3)")
class JiraAccessResultTest {
  private static final Grant GRANT =
      new JiraGrantGroups(new SourceInstanceId("jira-a"))
          .project("P1", List.of("U1"), JiraAccessScenario.NOW);
  private static final Grant LEVEL_GRANT =
      new JiraGrantGroups(new SourceInstanceId("jira-a"))
          .level("L1", List.of("U1"), JiraAccessScenario.NOW);
  private static final CallFailure FAILURE =
      CallFailure.of(CallFailure.Kind.INVALID_PROVIDER_RESPONSE, "Jira level incomplete");
  private static final AclPage OBSERVATIONS = new AclPage(Map.of(), List.of(), List.of());

  @ParameterizedTest
  @MethodSource("contradictoryDecisions")
  @DisplayName("판정과 grant·프로젝트 실패가 모순되면 결과 생성을 거부한다")
  void contradictoryDecisionIsRejected(
      Decision decision, Optional<Grant> grant, Optional<CallFailure> failure) {
    // when
    var rejected =
        catchThrowable(
            () ->
                new ProjectAccess(
                    "P1",
                    ProjectStyle.COMPANY_MANAGED,
                    decision,
                    grant,
                    Map.of(),
                    Set.of(),
                    OBSERVATIONS,
                    failure,
                    Optional.empty()));
    // then
    assertThat(rejected)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Inconsistent Jira project decision");
  }

  static Stream<Arguments> contradictoryDecisions() {
    return Stream.of(
        Arguments.of(Decision.SUPPORTED, Optional.empty(), Optional.empty()),
        Arguments.of(Decision.SUPPORTED, Optional.of(GRANT), Optional.of(FAILURE)),
        Arguments.of(Decision.POLICY_BLOCKED, Optional.of(GRANT), Optional.empty()),
        Arguments.of(Decision.POLICY_BLOCKED, Optional.empty(), Optional.of(FAILURE)),
        Arguments.of(Decision.OBSERVATION_FAILED, Optional.of(GRANT), Optional.of(FAILURE)),
        Arguments.of(Decision.OBSERVATION_FAILED, Optional.empty(), Optional.empty()));
  }

  @ParameterizedTest
  @EnumSource(
      value = ProjectStyle.class,
      names = {"TEAM_MANAGED", "UNKNOWN"})
  @DisplayName("지원하지 않는 프로젝트 유형은 성공 결과를 만들 수 없다")
  void unsupportedStyleCannotHaveGrant(ProjectStyle style) {
    // when
    var rejected =
        catchThrowable(
            () ->
                new ProjectAccess(
                    "P1",
                    style,
                    Decision.SUPPORTED,
                    Optional.of(GRANT),
                    Map.of(),
                    Set.of(),
                    OBSERVATIONS,
                    Optional.empty(),
                    Optional.empty()));
    // then
    assertThat(rejected)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Unsupported Jira project grant");
  }

  @ParameterizedTest
  @MethodSource("unavailableDetails")
  @DisplayName("프로젝트 미지원 결과에는 수준·이슈 조건·수준 실패를 담지 않는다")
  void unavailableProjectCannotHaveAccessDetails(
      Decision decision,
      Optional<CallFailure> failure,
      Map<String, Grant> levels,
      Set<IssueGrant> issues,
      Optional<CallFailure> levelFailure) {
    // when
    var rejected =
        catchThrowable(
            () ->
                new ProjectAccess(
                    "P1",
                    ProjectStyle.COMPANY_MANAGED,
                    decision,
                    Optional.empty(),
                    levels,
                    issues,
                    OBSERVATIONS,
                    failure,
                    levelFailure));
    // then
    assertThat(rejected)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Unavailable Jira project has access details");
  }

  static Stream<Arguments> unavailableDetails() {
    return Stream.of(
        Arguments.of(
            Decision.POLICY_BLOCKED,
            Optional.empty(),
            Map.of("L1", LEVEL_GRANT),
            Set.of(),
            Optional.empty()),
        Arguments.of(
            Decision.POLICY_BLOCKED,
            Optional.empty(),
            Map.of(),
            Set.of(new IssueGrant("reporter", "")),
            Optional.empty()),
        Arguments.of(
            Decision.POLICY_BLOCKED, Optional.empty(), Map.of(), Set.of(), Optional.of(FAILURE)),
        Arguments.of(
            Decision.OBSERVATION_FAILED,
            Optional.of(FAILURE),
            Map.of("L1", LEVEL_GRANT),
            Set.of(),
            Optional.empty()),
        Arguments.of(
            Decision.OBSERVATION_FAILED,
            Optional.of(FAILURE),
            Map.of(),
            Set.of(new IssueGrant("reporter", "")),
            Optional.empty()),
        Arguments.of(
            Decision.OBSERVATION_FAILED,
            Optional.of(FAILURE),
            Map.of(),
            Set.of(),
            Optional.of(FAILURE)));
  }

  @Test
  @DisplayName("실패한 수준 관측에는 확정 수준 grant를 담지 않는다")
  void failedLevelObservationCannotHaveLevelGrant() {
    // when
    var rejected =
        catchThrowable(
            () ->
                new ProjectAccess(
                    "P1",
                    ProjectStyle.COMPANY_MANAGED,
                    Decision.SUPPORTED,
                    Optional.of(GRANT),
                    Map.of("L1", LEVEL_GRANT),
                    Set.of(),
                    OBSERVATIONS,
                    Optional.empty(),
                    Optional.of(FAILURE)));
    // then
    assertThat(rejected)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Incomplete Jira levels have grants");
  }
}
