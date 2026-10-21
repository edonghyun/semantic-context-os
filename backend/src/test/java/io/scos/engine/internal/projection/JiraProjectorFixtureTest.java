package io.scos.engine.internal.projection;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.connector.jira.JiraAssembly;
import io.scos.connector.jira.JiraProjector;
import io.scos.engine.internal.projection.ProjectorFixture.DocView;
import io.scos.engine.internal.projection.ProjectorFixture.UnitView;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * F5 동등성(TESTING §3.6 4층): harness/fixtures/jira의 TS 첫 수집 원본(expected-raw-items.json) →
 * JiraProjector → expected-documents.json(TS JiraProjector)과 같고, 파이프라인을 거친 유닛은 expected-units.json과
 * 같다. 표본: ADF 본문·댓글·변경 이력·작업 기록·원격 링크·첨부 목록을 가진 이슈, 앱 사용자 정의 필드(정수 키), 64 KiB를 넘어 blob으로 저장된 이슈(변경
 * 이력 400건), 프로젝트·보드(scrum·kanban·simple)·스프린트·필터· 대시보드·컴포넌트·버전. 첨부·필드 원본은 문서가 되지 않는다.
 */
@DisplayName("Jira 투영기는 TS 원본에서 TS와 같은 문서·유닛을 낸다(F5, §3.6)")
class JiraProjectorFixtureTest {
  static final Path FIXTURES = JiraAssembly.FIXTURES;

  /** 이슈 7(40011은 blob 원본)·프로젝트 2·보드 3·스프린트 2·필터·대시보드·컴포넌트·버전 — 첨부 3·필드 2 원본은 묶이지 않는다. */
  static final int TS_DOCUMENTS = 18;

  final ProjectorFixture fixture =
      ProjectorFixture.of(
          FIXTURES, new JiraProjector(new ObjectMapper()), JiraAssembly.SOURCE.value());

  @Test
  @DisplayName("원본은 첨부·필드를 빼고 원본마다 문서 하나로 묶인다")
  void groupsOneDocumentPerResource() {
    // when
    List<String> documentIds = fixture.documentIds();

    // then
    assertThat(documentIds)
        .as("문서 묶음이 TS와 같다")
        .containsExactlyElementsOf(fixture.expectedDocumentIds());
    assertThat(documentIds).hasSize(TS_DOCUMENTS);
  }

  @Test
  @DisplayName("투영한 문서가 TS와 id·uri·제목·본문·시각까지 같다")
  void documentsMatchTs() {
    // when
    List<DocView> documents = fixture.documents();

    // then
    assertThat(documents).containsExactlyElementsOf(fixture.expectedDocuments());
  }

  @Test
  @DisplayName("투영한 문서를 파이프라인에 넣은 유닛이 TS 기대와 같다")
  void unitsMatchTs() {
    // given
    List<UnitView> expected = fixture.expectedUnits();
    if (expected.isEmpty())
      throw new IllegalStateException("given 준비 실패: 기대 유닛 사례(expected-units.json)가 있어야 한다");

    // when
    List<UnitView> units = fixture.units();

    // then
    assertThat(units).containsExactlyElementsOf(expected);
  }
}
