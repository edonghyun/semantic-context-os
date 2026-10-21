package io.scos.engine.internal.projection;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.connector.notion.NotionProjector;
import io.scos.engine.internal.projection.ProjectorFixture.DocView;
import io.scos.engine.internal.projection.ProjectorFixture.UnitView;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * F6 투영기 fixture 동등성(TESTING §3.6 4층): TS가 수집한 roots 첫 수집 원본(harness/fixtures/notion — 64 KiB 넘는
 * root-retro 페이지는 blob)을 Java NotionProjector에 넣어 묶은 문서·투영 결과·유닛이 TS와 같다.
 */
@DisplayName("Notion 투영기는 TS 원본에서 TS와 같은 문서·유닛을 낸다(F6, §3.6)")
class NotionProjectorFixtureTest {
  static final Path FIXTURES =
      Path.of(System.getProperty("scos.fixtures.notion.dir", "../harness/fixtures/notion"));

  final ProjectorFixture fixture =
      ProjectorFixture.of(FIXTURES, new NotionProjector(new ObjectMapper()), "notion-synthetic");

  @Test
  @DisplayName("페이지마다 문서 하나로 묶인다(블록은 조상 페이지로)")
  void groupsLikeTs() {
    // when
    List<String> documentIds = fixture.documentIds();

    // then
    assertThat(documentIds).containsExactlyElementsOf(fixture.expectedDocumentIds());
  }

  @Test
  @DisplayName("투영한 문서가 TS와 id·uri·제목·본문·시각까지 같다")
  void documentsMatchTs() {
    // given
    List<DocView> expected = fixture.expectedDocuments();
    if (expected.isEmpty())
      throw new IllegalStateException("given 준비 실패: 기대 문서(expected-documents.json)가 있어야 한다");

    // when
    List<DocView> documents = fixture.documents();

    // then
    assertThat(documents).containsExactlyElementsOf(expected);
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
