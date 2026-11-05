package io.scos.collection.internal.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.spi.connector.RawItem;
import io.scos.spi.connector.RawItems;
import io.scos.spi.projection.SourceDocument;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@DisplayName("투영 고장 어휘는 지정한 경계만 실패시킨다(TESTING §4.2)")
class FailingProjectorTest {
  private static final String DOC = "C1/day-2023-11-14";
  private static final RawItem MESSAGE =
      RawItems.item("C1:1700000000.000001")
          .version("1700000000.000001")
          .inlineJson("{\"text\":\"합성 메시지 본문\"}")
          .build();
  private final FailingProjector projector = new FailingProjector(new ObjectMapper());

  private Optional<SourceDocument> render() {
    return projector.project(
        "slack-a", DOC, List.of(MESSAGE), i -> Optional.of("{\"text\":\"합성 메시지 본문\"}"));
  }

  @Test
  @DisplayName("다른 문서를 실패 대상으로 지정해도 정상 문서는 렌더한다")
  void otherDocumentStillRenders() {
    // given
    projector.failDocument("C2/day-2023-11-14");
    // when
    var result = render();
    // then
    assertThat(result).isPresent();
  }

  @Test
  @DisplayName("지정한 문서의 렌더링에 고유 장애를 주입한다")
  void injectsSelectedRenderFailure() {
    // given
    projector.failDocument(DOC);
    // when·then
    assertThatThrownBy(this::render)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("injected render failure");
  }

  @Test
  @DisplayName("그룹 묶기 장애는 렌더 장애와 구분한다")
  void injectsGroupingFailure() {
    // given
    projector.failGrouping();
    // when·then
    assertThatThrownBy(() -> projector.groupIntoDocuments(List.of(MESSAGE)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("injected grouping failure");
  }
}
