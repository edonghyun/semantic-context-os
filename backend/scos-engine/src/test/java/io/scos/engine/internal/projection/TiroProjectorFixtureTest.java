package io.scos.engine.internal.projection;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.connector.tiro.TiroProjector;
import io.scos.engine.internal.projection.ProjectorFixture.DocView;
import io.scos.engine.internal.projection.ProjectorFixture.UnitView;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * F8 동등성(TESTING §3.6 4층): harness/fixtures/tiro의 TS 원본 항목(expected-raw-items.json) → TiroProjector
 * → expected-documents.json(TS TiroProjector)과 같고, 파이프라인을 거친 유닛은 expected-units.json과 같다. 표본: 화자·잠긴
 * 문단·요약·회의 문서, 요약 접근 제한, 문단 접근 제한과 tiro.ooo 밖 webUrl, 템플릿 없는 문서. 읽기·투영·비교 값은 공용 {@link
 * ProjectorFixture}다.
 */
@DisplayName("Tiro 투영기는 TS 원본에서 TS와 같은 문서·유닛을 낸다(F8, §3.6)")
class TiroProjectorFixtureTest {
  static final Path FIXTURES =
      Path.of(System.getProperty("scos.fixtures.tiro.dir", "../harness/fixtures/tiro"));

  /** 노트 4건(n-locked·n-gone은 상세 403·404로 tombstone이라 원본이 없다). */
  static final int TS_DOCUMENTS = 4;

  final ProjectorFixture fixture =
      ProjectorFixture.of(FIXTURES, new TiroProjector(new ObjectMapper()), "tiro-synthetic");

  @Test
  @DisplayName("노트 원본 4건은 노트마다 문서 하나로 묶인다")
  void groupsOneDocumentPerNote() {
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
