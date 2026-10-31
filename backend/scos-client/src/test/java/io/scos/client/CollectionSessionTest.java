package io.scos.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("수집 세션은 정상·저하 경로에서 원문과 잠금 수명을 함께 지킨다(C6)")
class CollectionSessionTest {
  @Test
  @DisplayName("색인 없는 수집은 두 원천 문서를 모두 투영 대기로 남긴다")
  void stateOnlyKeepsPendingDocuments(@TempDir Path root) throws Exception {
    // given
    try (var scenario = new ScosLocalScenario(root)) {
      scenario.givenProject();
      // when
      scenario.collectWithoutIndex();

      // then
      assertThat(scenario.pending())
          .isEqualTo(
              Map.of(
                  "claude-transcript", Set.of("transcript/d/s1"),
                  "claude-memory", Set.of("memory/d/a.md")));
    }
  }

  @Test
  @DisplayName("색인 없는 수집은 색인 파일을 만들지 않는다")
  void stateOnlyDoesNotCreateIndex(@TempDir Path root) throws Exception {
    // given
    try (var scenario = new ScosLocalScenario(root)) {
      scenario.givenProject();
      // when
      scenario.collectWithoutIndex();

      // then
      assertThat(scenario.index()).doesNotExist();
    }
  }

  @Test
  @DisplayName("색인 없는 수집을 닫으면 다음 수집자가 잠금을 잡을 수 있다")
  void stateOnlyReleasesLock(@TempDir Path root) throws Exception {
    // given
    try (var scenario = new ScosLocalScenario(root)) {
      scenario.givenProject();
      // when
      scenario.collectWithoutIndex();

      // then
      assertThat(scenario.lockAvailable()).isTrue();
    }
  }

  @Test
  @DisplayName("저하 수집 뒤 정상 기동하면 두 원천의 저장된 문서를 검색한다")
  void recoveredIndexIncludesBothSources(@TempDir Path root) throws Exception {
    // given
    try (var scenario = new ScosLocalScenario(root)) {
      scenario.givenProject();
      scenario.collectWithoutIndex();

      // when
      var documents = scenario.recover();

      // then
      assertThat(documents)
          .isEqualTo(
              Map.of("session", List.of("transcript/d/s1"), "memory", List.of("memory/d/a.md")));
    }
  }

  @Test
  @DisplayName("수집기 생성 실패 뒤 다음 주기는 잠금과 state를 다시 열어 수집한다")
  void retriesFailedAcquisition(@TempDir Path root) throws Exception {
    // given
    try (var scenario = new ScosLocalScenario(root)) {
      scenario.givenProject();
      scenario.givenFailedAcquisition();

      // when
      var report = scenario.collectWithLock();

      // then
      assertThat(report.documentsProjected()).isEqualTo(2);
    }
  }

  @Test
  @DisplayName("저하 경로는 원천 루트가 없으면 state를 열지 않는다")
  void stateOnlySkipsStateWhenRootIsMissing(@TempDir Path root) throws Exception {
    // given
    try (var scenario = new ScosLocalScenario(root)) {
      scenario.givenProject();
      scenario.givenMissingProjectsRoot();

      // when
      scenario.collectWithoutIndex();

      // then
      assertThat(scenario.state()).doesNotExist();
    }
  }
}
