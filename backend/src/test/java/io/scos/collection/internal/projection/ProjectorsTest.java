package io.scos.collection.internal.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.connector.slack.SlackProjector;
import io.scos.engine.internal.projection.NotesProjector;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** kind → 투영기 레지스트리(ADR-0022). */
@DisplayName("투영기 레지스트리는 kind마다 투영기 하나를 둔다(ADR-0022)")
class ProjectorsTest {
  static final SlackProjector SLACK = new SlackProjector(new ObjectMapper());
  static final NotesProjector NOTES = new NotesProjector();

  @Test
  @DisplayName("등록된 kind의 투영기를 찾는다")
  void findsRegisteredKind() {
    // given
    Projectors projectors = Projectors.of(List.of(SLACK, NOTES));

    // when·then
    assertThat(projectors.forKind(NotesProjector.KIND)).containsSame(NOTES);
  }

  @Test
  @DisplayName("등록되지 않은 kind는 비어 있다")
  void unknownKindIsEmpty() {
    // given
    Projectors projectors = Projectors.of(List.of(SLACK));

    // when·then
    assertThat(projectors.forKind("jira")).isEmpty();
  }

  @Test
  @DisplayName("같은 kind의 투영기가 둘이면 조립 시점에 거부한다")
  void rejectsDuplicateKind() {
    // when·then
    assertThatThrownBy(() -> Projectors.of(List.of(NOTES, new NotesProjector())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(NotesProjector.KIND);
  }
}
