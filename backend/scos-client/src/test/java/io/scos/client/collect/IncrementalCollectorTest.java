package io.scos.client.collect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import io.scos.client.ScosLocalScenario;
import io.scos.engine.ContextHit;
import io.scos.spi.store.Tier;
import io.scos.support.LogCapture;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;

@DisplayName("증분 수집기는 운영 원천 배선으로 문서를 보존하고 투영한다(D2, D8, D9, E2)")
// JVM 전역 제품 로거의 appender에 다른 수집기 테스트의 로그가 섞이지 않게 한다.
@Isolated
class IncrementalCollectorTest {
  private static final String PASSWORD = "hunter2syntheticpw";
  private static final String KEY_BODY = "MIIBSYNTHETICKEYBODYAAAA";
  private static final String SECRET = "sk-ant-api03-ZZZZSYNTHETICSECRETVALUE0123456789";
  private static final String SESSION_CONTENT =
      """
      ## 턴 2 · 2026-10-27T10:00:06Z

      자동 메모리는 대체하지 않고 보완하자

      - 도구 Bash: ./gradlew test

      사용자: 대체는 별로\
      """;
  private static final String MEMORY_CONTENT =
      """
      # 고양이 메모

      보라색 고양이는 사용자가 좋아하는 합성 예시이며 검색 확인용으로만 쓴다.\
      """;
  // 고정 fixture의 식별자와 단일 키워드 1위 점수를 독립적으로 계산해 적는다.
  private static final ContextHit SESSION_HIT =
      new ContextHit(
          "af543122a9dccf26",
          "transcript/d/s1",
          "턴 2 · 2026-10-27T10:00:06Z",
          SESSION_CONTENT,
          "claude-transcript://d/s1",
          "claude-transcript",
          "2026-10-27T10:00:17.000Z",
          Tier.RAW,
          0.01639344262295082);
  private static final ContextHit MEMORY_HIT =
      new ContextHit(
          "7124b9f9f04b7308",
          "memory/d/a.md",
          "고양이 메모",
          MEMORY_CONTENT,
          "claude-memory://d/a.md",
          "claude-memory",
          "2026-10-28T00:00:00Z",
          Tier.RAW,
          0.01639344262295082);
  @TempDir Path root;
  private ScosLocalScenario scenario;

  @BeforeEach
  void setUp() throws IOException {
    scenario = new ScosLocalScenario(root);
    scenario.givenProject();
  }

  @AfterEach
  void tearDown() {
    scenario.close();
  }

  @Test
  @DisplayName("한 번 수집하면 두 원천 문서를 투영한다")
  void collectsAndProjects() {
    // given: 합성 세션과 메모리
    // when
    CollectionReport report = scenario.collect();
    // then
    assertThat(report.documentsProjected()).isEqualTo(2);
  }

  @Test
  @DisplayName("서로 다른 페이지의 저장한 원본 수를 합쳐 보고한다")
  void reportsUpsertsAcrossSources() throws IOException {
    // given
    scenario.givenSmallProjectWithRejectedLine();
    // when
    CollectionReport report = scenario.collect();
    // then
    assertThat(report.upserts()).isEqualTo(3);
  }

  @Test
  @DisplayName("깨진 트랜스크립트 줄은 거절 수에 한 번만 센다")
  void reportsRejectedLine() throws IOException {
    // given
    scenario.givenSmallProjectWithRejectedLine();
    // when
    CollectionReport report = scenario.collect();
    // then
    assertThat(report.rejected()).isEqualTo(1);
  }

  @Test
  @DisplayName("지운 메모리 원본 수를 삭제 보고에 센다")
  void reportsDeletedRawItem() throws IOException {
    // given
    scenario.givenCollectedDocuments();
    scenario.deleteMemory();
    // when
    CollectionReport report = scenario.collect();
    // then
    assertThat(report.deletes()).isEqualTo(1);
  }

  @Test
  @DisplayName("재투영 시작 전에 취소되면 작업 없는 보고를 돌려준다")
  void cancelledReprojectionReportsNoWork() {
    // given
    scenario.givenCollectedDocuments();
    scenario.cancelBeforeCollection();
    // when
    CollectionReport report = scenario.fullReproject();
    // then
    assertThat(report).isEqualTo(new CollectionReport(0, 0, 0, 0, 0, 0, 0, 0));
  }

  @Test
  @DisplayName("수집한 세션은 트랜스크립트 출처와 주소로 검색된다")
  void searchesTranscript() {
    // given
    scenario.collect();
    // when
    var hits = scenario.search("대체는 별로");
    // then
    assertThat(hits)
        .extracting(ContextHit::documentId, ContextHit::sourceId, ContextHit::uri)
        .containsExactly(tuple("transcript/d/s1", "claude-transcript", "claude-transcript://d/s1"));
  }

  @Test
  @DisplayName("원본 jsonl이 지워져도 세션은 검색에 남는다")
  void survivesTranscriptCleanup() throws IOException {
    // given
    scenario.givenCollectedDocuments();
    scenario.deleteSession();
    // when
    scenario.collect();
    // then
    assertThat(scenario.ids("대체는 별로")).containsExactly("transcript/d/s1");
  }

  @Test
  @DisplayName("지운 메모리 파일의 유닛은 사라진다")
  void removesDeletedMemory() throws IOException {
    // given
    scenario.givenCollectedDocuments();
    scenario.deleteMemory();
    // when
    scenario.collect();
    // then
    assertThat(scenario.ids("보라색 고양이")).isEmpty();
  }

  @Test
  @DisplayName("메모리 파일을 지우면 삭제 유닛 하나를 보고한다")
  void reportsDeletedMemory() throws IOException {
    // given
    scenario.givenCollectedDocuments();
    scenario.deleteMemory();
    // when
    CollectionReport report = scenario.collect();
    // then
    assertThat(report.unitsDeleted()).isEqualTo(1);
  }

  @Test
  @DisplayName("색인을 지우고 재투영하면 두 원천 문서를 복원한다")
  void rebuildsIndexFromState() throws IOException {
    // given
    scenario.givenCollectedDocuments();
    scenario.deleteIndex();
    // when
    scenario.fullReproject();
    // then
    assertThat(scenario.search("대체는 별로")).isEqualTo(List.of(SESSION_HIT));
    assertThat(scenario.search("보라색 고양이")).isEqualTo(List.of(MEMORY_HIT));
  }

  @Test
  @DisplayName("색인 재투영은 복원한 문서 둘을 보고한다")
  void reportsReprojectedDocuments() throws IOException {
    // given
    scenario.givenCollectedDocuments();
    scenario.deleteIndex();
    // when
    CollectionReport report = scenario.fullReproject();
    // then
    assertThat(report.documentsProjected()).isEqualTo(2);
  }

  @Test
  @DisplayName("가린 원문만 state와 색인 및 검색 결과에 있다")
  void noSecretsAnywhere() throws IOException {
    // given
    scenario.appendUser(
        "열쇠 "
            + SECRET
            + " \"password="
            + PASSWORD
            + "\" -----BEGIN RSA PRIVATE KEY-----\n"
            + KEY_BODY
            + "\n-----END RSA PRIVATE KEY----- 비밀검색어");
    scenario.writeMemory("비밀메모 " + SECRET + "\n");
    // when
    scenario.collect();
    // then
    assertThat(scenario.search("비밀검색어 비밀메모").toString())
        .contains("비밀검색어")
        .doesNotContain(SECRET, PASSWORD, KEY_BODY);
    assertThat(scenario.storedStateBytes())
        .contains("비밀검색어")
        .doesNotContain(SECRET, PASSWORD, KEY_BODY);
    assertThat(scenario.storedIndexBytes()).doesNotContain(SECRET, PASSWORD, KEY_BODY);
  }

  @Test
  @DisplayName("투영 실패 뒤 정상 실행하면 대기 문서를 검색한다")
  void resumesPendingProjection() {
    // given
    scenario.failsProjection();
    scenario.collect();
    scenario.resumesNormally();
    // when
    scenario.collect();
    // then
    assertThat(scenario.ids("대체는 별로")).containsExactly("transcript/d/s1");
    assertThat(scenario.pendingTranscripts()).isEmpty();
  }

  @Test
  @DisplayName("색인이 늘 실패해도 새 줄을 state에 저장한다")
  void stateAdvancesWhileIndexAlwaysFails() throws IOException {
    // given
    scenario.failsProjection();
    scenario.collect();
    scenario.appendUser("새로 붙은 줄");
    // when
    CollectionReport report = scenario.collect();
    // then
    assertThat(report.upserts()).isEqualTo(1);
    assertThat(report.failedSources()).isZero();
    assertThat(scenario.pendingTranscripts()).containsExactly("transcript/d/s1");
  }

  @Test
  @DisplayName("투영 실패 보고에는 실패한 문서 둘과 성공 문서 영 개가 남는다")
  void reportsFailedProjection() {
    // given
    scenario.failsProjection();
    // when
    CollectionReport report = scenario.collect();
    // then
    assertThat(report.documentsFailed()).isEqualTo(2);
    assertThat(report.documentsProjected()).isZero();
  }

  @Test
  @DisplayName("비워진 메모리 파일의 옛 유닛은 지워진다")
  void emptiedMemoryLosesUnits() throws IOException {
    // given
    scenario.givenCollectedDocuments();
    scenario.writeMemory("");
    // when
    scenario.collect();
    // then
    assertThat(scenario.ids("보라색 고양이")).isEmpty();
  }

  @Test
  @DisplayName("첫 문서에서 재투영을 취소하면 메모리는 아직 검색되지 않는다")
  void cancelsAfterFirstDocument() throws IOException {
    // given
    scenario.givenCollectedDocuments();
    scenario.deleteIndex();
    scenario.cancelsAfterFirstDocument();
    // when
    scenario.fullReproject();
    // then
    assertThat(scenario.ids("대체는 별로")).containsExactly("transcript/d/s1");
    assertThat(scenario.ids("보라색 고양이")).isEmpty();
  }

  @Test
  @DisplayName("중단된 재투영 다음 실행은 나머지 문서를 복원한다")
  void resumesInterruptedReprojection() throws IOException {
    // given
    scenario.givenCollectedDocuments();
    scenario.deleteIndex();
    scenario.cancelsAfterFirstDocument();
    scenario.fullReproject();
    scenario.resumesNormally();
    // when
    scenario.collect();
    // then
    assertThat(scenario.search("대체는 별로")).isEqualTo(List.of(SESSION_HIT));
    assertThat(scenario.search("보라색 고양이")).isEqualTo(List.of(MEMORY_HIT));
  }

  @Test
  @DisplayName("한 원천이 실패해도 다음 원천과 커밋한 페이지를 검색한다")
  void continuesAfterFailedSource() {
    // given
    scenario.failsAfterTranscriptCollection();
    // when
    scenario.collect();
    // then
    assertThat(scenario.ids("보라색 고양이")).containsExactly("memory/d/a.md");
    assertThat(scenario.ids("대체는 별로")).containsExactly("transcript/d/s1");
  }

  @Test
  @DisplayName("원천 하나의 실패는 보고에 한 번 남는다")
  void reportsFailedSource() {
    // given
    scenario.failsAfterTranscriptCollection();
    // when
    CollectionReport report = scenario.collect();
    // then
    assertThat(report.failedSources()).isEqualTo(1);
  }

  @Test
  @DisplayName("어댑터 실패 뒤 투영도 실패해도 원래 어댑터 실패를 보고한다")
  void projectionFailureDoesNotMaskAdapterFailure() {
    // given
    scenario.failsAfterTranscriptCollection();
    scenario.failsProjection();
    try (var logs = LogCapture.of(IncrementalCollector.class)) {
      // when
      scenario.collect();
      // then
      assertThat(logs.events("client.source.failed"))
          .extracting(event -> event.path("detail").path("errorType").asString())
          .containsExactly("IllegalArgumentException");
    }
  }

  @Test
  @DisplayName("격리한 트랜스크립트는 대기 목록에 남기고 검색에서 제외한다")
  void keepsIsolatedDocumentsPending() {
    // given
    scenario.isolatesTranscripts();
    // when
    scenario.collect();
    // then
    assertThat(scenario.pendingTranscripts()).containsExactly("transcript/d/s1");
    assertThat(scenario.ids("대체는 별로")).isEmpty();
  }

  @Test
  @DisplayName("격리 보고는 성공과 실패 문서를 하나씩 센다")
  void reportsIsolatedDocuments() {
    // given
    scenario.isolatesTranscripts();
    // when
    CollectionReport report = scenario.collect();
    // then
    assertThat(report.documentsFailed()).isEqualTo(1);
    assertThat(report.documentsProjected()).isEqualTo(1);
  }

  @Test
  @DisplayName("격리돼 대기 중인 문서는 다음 주기에 투영된다")
  void retriesIsolatedDocuments() {
    // given
    scenario.isolatesTranscripts();
    scenario.collect();
    scenario.resumesNormally();
    // when
    scenario.collect();
    // then
    assertThat(scenario.ids("대체는 별로")).containsExactly("transcript/d/s1");
    assertThat(scenario.pendingTranscripts()).isEmpty();
  }
}
