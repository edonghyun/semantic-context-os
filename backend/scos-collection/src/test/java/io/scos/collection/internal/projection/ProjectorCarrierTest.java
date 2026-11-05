package io.scos.collection.internal.projection;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("투영 묶음은 문서의 모든 carrier를 포함한다(CONTRACTS T-b9b)")
class ProjectorCarrierTest {
  @ParameterizedTest(name = "{0}")
  @MethodSource("policies")
  @DisplayName("각 커넥터의 묶음은 선언한 모든 제한 carrier를 보존한다")
  void completeBundlesSatisfyAccessPolicy(String kind, ProjectorCarrierScenario scenario) {
    // when
    var invalid = scenario.invalidDocuments();
    // then
    assertThat(invalid).isEmpty();
  }

  static Stream<Arguments> policies() {
    return Stream.of(
        Arguments.of("slack", ProjectorCarrierScenario.slack()),
        Arguments.of("google-drive", ProjectorCarrierScenario.drive()),
        Arguments.of("jira", ProjectorCarrierScenario.jira()),
        Arguments.of("tiro", ProjectorCarrierScenario.tiro()),
        Arguments.of("notion", ProjectorCarrierScenario.notion()));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("omissions")
  @DisplayName("커넥터가 제한 carrier를 빠뜨리면 그 문서의 정책 위반을 감지한다")
  void missingCarrierViolatesAccessPolicy(
      String kind, ProjectorCarrierScenario scenario, String omitted, String document) {
    // when
    var invalid = scenario.invalidAfterOmittingCarrier(omitted);
    // then
    assertThat(invalid).containsExactly(document);
  }

  static Stream<Arguments> omissions() {
    return Stream.of(
        Arguments.of(
            "slack", ProjectorCarrierScenario.slack(), "C1:1760400000.000100", "C1/day-2025-10-14"),
        Arguments.of("google-drive", ProjectorCarrierScenario.drive(), "file:F1", "file:F1"),
        Arguments.of("jira", ProjectorCarrierScenario.jira(), "issue:ACME-1", "issue:ACME-1"),
        Arguments.of("tiro", ProjectorCarrierScenario.tiro(), "note:N1", "note:N1"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"{\"channelId\":\"C1\"}", "{\"messageTs\":\"1760400000.000100\"}"})
  @DisplayName("문서 식별에 필요한 메타데이터가 없는 Slack carrier는 문서가 되지 않는다")
  void unclassifiableSlackCarrierDoesNotBecomeDocument(String metadata) {
    var scenario = ProjectorCarrierScenario.slackWithMetadata(metadata);
    // when
    var documents = scenario.documentIds();
    // then
    assertThat(documents).isEmpty();
  }

  @Nested
  @DisplayName("각 커넥터의 정상 원본 집합에서")
  class CompleteSources {
    @ParameterizedTest(name = "{0}")
    @MethodSource("bundles")
    @DisplayName("문서를 묶으면 접근을 제한하는 carrier가 모두 같은 묶음에 남는다")
    void everyDocumentBundleCarriesItsCarriers(
        String kind, ProjectorCarrierScenario scenario, Map<String, List<String>> expected) {
      // when
      var carriers = scenario.groupedCarrierIds();
      // then
      assertThat(carriers).isEqualTo(expected);
    }

    static Stream<Arguments> bundles() {
      return Stream.of(
          slackBundle(),
          driveBundle(),
          jiraBundle(),
          tiroBundle(),
          Arguments.of("notion", ProjectorCarrierScenario.notion(), Map.of("P1", List.of())));
    }

    private static Arguments slackBundle() {
      return Arguments.of(
          "slack",
          ProjectorCarrierScenario.slack(),
          Map.of(
              "C1/day-2025-10-14", List.of("C1:1760400000.000100", "C1:1760400000.000200"),
              "C1/thread-1760400000.000300",
                  List.of("C1:1760400000.000300", "C1:1760400000.000400"),
              "C2/day-2025-10-14", List.of("C2:1760400000.000100")));
    }

    private static Arguments driveBundle() {
      return Arguments.of(
          "google-drive",
          ProjectorCarrierScenario.drive(),
          Map.of(
              "file:F1",
              List.of("file:F1"),
              "file:F2",
              List.of("file:F2"),
              "drive:D1",
              List.of("drive:D1")));
    }

    private static Arguments jiraBundle() {
      return Arguments.of(
          "jira",
          ProjectorCarrierScenario.jira(),
          Map.of(
              "issue:ACME-1",
              List.of("issue:ACME-1"),
              "project:P1",
              List.of("project:P1"),
              "component:C1",
              List.of("component:C1"),
              "version:V1",
              List.of("version:V1"),
              "filter:F1",
              List.of("filter:F1"),
              "dashboard:D1",
              List.of("dashboard:D1"),
              "board:B1",
              List.of("board:B1"),
              "sprint:S1",
              List.of("sprint:S1")));
    }

    private static Arguments tiroBundle() {
      return Arguments.of(
          "tiro",
          ProjectorCarrierScenario.tiro(),
          Map.of("note:N1", List.of("note:N1"), "note:N2", List.of("note:N2")));
    }
  }

  @Nested
  @DisplayName("carrier 없이 조각만 남은 원본 집합에서")
  class FragmentsOnly {
    @ParameterizedTest(name = "{0}")
    @MethodSource("fragments")
    @DisplayName("문서를 묶으면 조각은 독립 문서가 되지 않는다")
    void fragmentsDoNotBecomeDocuments(String kind, ProjectorCarrierScenario scenario) {
      // when
      var documents = scenario.documentIds();
      // then
      assertThat(documents).isEmpty();
    }

    static Stream<Arguments> fragments() {
      return Stream.of(
          Arguments.of("slack non-message", ProjectorCarrierScenario.slackFragments()),
          Arguments.of("google-drive sheet", ProjectorCarrierScenario.driveFragments()),
          Arguments.of("jira attachment", ProjectorCarrierScenario.jiraFragments()));
    }
  }
}
