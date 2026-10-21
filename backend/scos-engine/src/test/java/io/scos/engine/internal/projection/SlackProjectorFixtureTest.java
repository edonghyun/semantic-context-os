package io.scos.engine.internal.projection;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.connector.slack.SlackProjector;
import io.scos.engine.internal.projection.ProjectorFixture.UnitView;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * G2 동등성: harness/fixtures/p4의 TS 원본 항목(expected-raw-items.json) → SlackProjector →
 * expected-documents.json(TS SlackProjector)과 같고, 파이프라인을 거친 유닛은 expected-units.json과 같다. 표본: 일별·스레드
 * 문서, 편집(edited.ts = sourceVersion), 빈 본문, mrkdwn(굵게·링크·멘션· 이모지·인라인 코드·펜스), 스레드 root 포함. 읽기·투영·비교
 * 값은 공용 {@link ProjectorFixture}다(TESTING §3.6).
 */
@DisplayName("Slack 투영기는 TS 원본에서 TS와 같은 문서·유닛을 낸다(G2)")
class SlackProjectorFixtureTest {
  static final Path FIXTURES =
      Path.of(System.getProperty("scos.fixtures.p4.dir", "../harness/fixtures/p4"));
  static final int TS_DOCUMENTS = 3;

  final ProjectorFixture fixture =
      ProjectorFixture.of(FIXTURES, new SlackProjector(new ObjectMapper()), "slack-synthetic");

  @Test
  @DisplayName("원본 9건은 문서 3건(일별 2·스레드 1)으로 묶인다")
  void groupsIntoThreeDocuments() {
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
    List<ProjectorFixture.DocView> documents = fixture.documents();

    // then
    assertThat(documents).containsExactlyElementsOf(fixture.expectedDocuments());
  }

  @Test
  @DisplayName("투영한 문서를 파이프라인에 넣은 유닛이 TS 기대(expected-units.json)와 같다")
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
