package io.scos.connector.notion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.ObjectMapper;

/**
 * Notion 투영기 경계(TS NotionProjector, 표 expected-projector.json): 표본마다 원본 묶음과 문서가 TS와 같다 — 제목 없음, 빈
 * 페이지, uri 대체, 시각 대체·최댓값, 하위 페이지, database 행과 container, 부모 페이지가 없는 블록, 순환, 페이지가 아닌 문서 id.
 */
@DisplayName("Notion 투영기는 경계 사례에서 묶음·문서가 TS와 같다(F6)")
class NotionProjectorTest {
  final NotionProjector projector = new NotionProjector(new ObjectMapper());

  static Stream<NotionProjectorCases> cases() {
    return NotionProjectorCases.all();
  }

  @Test
  @DisplayName("notion 원본을 맡는다")
  void kindIsNotion() {
    // when·then
    assertThat(projector.kind()).isEqualTo("notion");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  @DisplayName("원본 묶음이 TS groupIntoDocuments와 같다")
  void groupsLikeTs(NotionProjectorCases row) {
    // when·then
    assertThat(row.groups(projector)).isEqualTo(row.expectedGroups());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  @DisplayName("문서가 TS project와 같다")
  void documentsMatchTs(NotionProjectorCases row) {
    // when·then
    assertThat(row.documents(projector)).containsExactlyElementsOf(row.expectedDocuments());
  }
}
