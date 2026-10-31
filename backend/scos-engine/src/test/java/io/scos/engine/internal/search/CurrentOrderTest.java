package io.scos.engine.internal.search;

import static io.scos.spi.store.ContextUnits.unit;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Current 순서는 원래 관련도 점수를 보존한다(CONTRACTS B12·C6)")
class CurrentOrderTest {
  private static final double OLDER_RELEVANCE_SCORE = 0.9;
  private static final double NEWER_RELEVANCE_SCORE = 0.8;
  private final Candidate older =
      new Candidate(unit("old").capturedAt("2026-10-26T00:00:00Z").build(), OLDER_RELEVANCE_SCORE);
  private final Candidate newer =
      new Candidate(unit("new").capturedAt("2026-10-27T00:00:00Z").build(), NEWER_RELEVANCE_SCORE);

  @Test
  @DisplayName("검색 순서를 바꾸면 유닛과 점수는 원래 값 그대로다")
  void searchPreservesCandidates() {
    // given
    var candidates = List.of(older, newer);
    // when
    var result = CurrentOrder.search(candidates, 2);
    // then
    assertThat(result).containsExactly(newer, older);
  }

  @Test
  @DisplayName("조립 순서를 바꾸면 유닛과 점수는 원래 값 그대로다")
  void assemblyPreservesItems() {
    // given
    var oldItem = new ContextAssembler.Item(older.unit(), OLDER_RELEVANCE_SCORE);
    var newItem = new ContextAssembler.Item(newer.unit(), NEWER_RELEVANCE_SCORE);
    // when
    var result = CurrentOrder.assemble(List.of(oldItem, newItem));
    // then
    assertThat(result).containsExactly(newItem, oldItem);
  }
}
