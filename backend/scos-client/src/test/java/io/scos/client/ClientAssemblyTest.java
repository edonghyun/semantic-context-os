package io.scos.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("로컬 조립은 잠금을 지키며 수집하고 저장한 문서를 복구한다(D2, E2)")
class ClientAssemblyTest {
  @TempDir Path root;
  private ScosLocalScenario scenario;

  @BeforeEach
  void setUp() {
    scenario = new ScosLocalScenario(root);
  }

  @AfterEach
  void close() {
    scenario.close();
  }

  @Test
  @DisplayName("빈 홈에서 조립하면 엔진이 빈 결과로 답한다")
  void assemblesOnEmptyHome() {
    // given: 홈도 원천 폴더도 없다
    // when
    var hits = scenario.assemblyIds("대체는 별로");
    // then
    assertThat(hits).isEmpty();
  }

  @Test
  @DisplayName("잠금을 잡고 수집하면 두 원천 문서를 투영한다")
  void collectsWithLock() throws IOException {
    // given
    scenario.givenProject();
    // when
    var report = scenario.collectWithLock();
    // then
    assertThat(report.documentsProjected()).isEqualTo(2);
  }

  @Test
  @DisplayName("한 주기를 수집하면 세션과 메모리가 검색된다")
  void searchesBothSources() throws IOException {
    // given
    scenario.givenProject();
    // when
    var documents = scenario.recover();
    // then
    assertThat(documents)
        .isEqualTo(
            Map.of("session", List.of("transcript/d/s1"), "memory", List.of("memory/d/a.md")));
  }

  @Test
  @DisplayName("다른 보유자가 잠금을 쥐면 수집하지 않고 색인에 쓰지 않는다")
  void skipsWithoutLock() throws IOException {
    // given
    scenario.givenProject();
    try (var held = scenario.holdLock()) {
      // when
      var report = scenario.tryCollectWithLock();
      // then
      assertThat(report).isEmpty();
      assertThat(scenario.assemblyIds("대체는 별로")).isEmpty();
      assertThat(scenario.state()).doesNotExist();
    }
  }

  @Test
  @DisplayName("색인을 지우면 다음 기동이 두 원천 문서를 다시 투영한다")
  void reprojectsIntoEmptyIndex() throws IOException {
    // given
    scenario.givenProject();
    scenario.collectWithLock();
    scenario.deleteIndex();
    // when
    var documents = scenario.recover();
    // then
    assertThat(documents)
        .isEqualTo(
            Map.of("session", List.of("transcript/d/s1"), "memory", List.of("memory/d/a.md")));
  }

  @Test
  @DisplayName("원천 루트가 사라지면 메모리 유닛을 지우지 않는다")
  void skipsMissingProjectsRoot() throws IOException {
    // given
    scenario.givenProject();
    scenario.collectWithLock();
    scenario.givenMissingProjectsRoot();
    // when
    var report = scenario.collectWithLock();
    // then
    assertThat(report.unitsDeleted()).isZero();
    assertThat(scenario.assemblyIds("보라색 고양이")).containsExactly("memory/d/a.md");
  }

  @Test
  @DisplayName("옛 설치를 마이그레이션하면 메모리 시각은 파일 수정 시각이다")
  void migrationRepairsMemoryTime() throws Exception {
    // given
    scenario.givenProject();
    scenario.givenVersionOneInstallation();
    // when
    scenario.collectWithLock();
    // then
    assertThat(scenario.capturedAt("보라색 고양이", "memory/d/a.md")).isEqualTo("2026-09-01T03:04:05Z");
  }

  @Test
  @DisplayName("업그레이드 전에 삭제된 메모리는 마이그레이션 수집 뒤 검색되지 않는다(B2)")
  void migrationRemovesDeletedMemory() throws Exception {
    // given
    scenario.givenProject();
    scenario.givenVersionOneInstallation();
    scenario.deleteMemory();
    // when
    scenario.collectWithLock();
    // then
    assertThat(scenario.assemblyIds("보라색 고양이")).isEmpty();
  }

  @Test
  @DisplayName("옛 설치를 마이그레이션하면 빈 트랜스크립트 시각을 복구한다")
  void migrationRepairsTranscriptTime() throws Exception {
    // given
    scenario.givenProject();
    scenario.givenVersionOneInstallation();
    // when
    scenario.collectWithLock();
    // then
    assertThat(scenario.capturedAt("대체는 별로", "transcript/d/s1"))
        .isEqualTo("2026-10-27T10:00:17.000Z");
  }

  @Test
  @DisplayName("첫 주기 전에 종료 신호가 오면 수집하지 않는다")
  void cancelBeforeFirstCycleSkipsCollection() throws IOException {
    // given
    scenario.givenProject();
    scenario.cancelCollection();
    // when
    var report = scenario.collectWithLock();
    // then
    assertThat(report.documentsProjected()).isZero();
    assertThat(scenario.assemblyIds("대체는 별로")).isEmpty();
  }

  @Test
  @DisplayName("질의 로그에는 자격 증명을 가린 질의만 남는다")
  void queryLogKeepsOnlyRedactedQueries() throws IOException {
    // given: 검색 질의에 password가 있다
    // when
    String query = scenario.loggedSearch("배포 password=hunter2secret 실패");
    // then
    assertThat(query).isEqualTo("배포 password=[REDACTED] 실패");
  }
}
