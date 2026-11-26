package io.scos.engine;

import static io.scos.spi.store.ContextUnits.unit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import io.scos.engine.internal.search.SearchFixture;
import io.scos.spi.store.Principal;
import io.scos.support.TestExecutors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("read·related·assemble 결과는 문서의 원천 id를 싣는다")
class ResultSourceTest {
  static final Principal LOCAL = new Principal("local");
  final SearchFixture fixture = new SearchFixture();
  final ContextEngine engine;

  ResultSourceTest() {
    fixture.load(
        SearchFixture.S,
        unit("notice-1")
            .inDocument("notice")
            .titled("점검")
            .content("점검 안내")
            .source("board-a")
            .linksTo("patch")
            .build(),
        unit("patch-1")
            .inDocument("patch")
            .titled("패치")
            .content("패치 노트")
            .source("board-b")
            .build());
    engine =
        ContextEngine.builder()
            .units(fixture.units)
            .keywords(fixture.keywords)
            .unrestricted(fixture.maintenance)
            .keywordOnly()
            .lexicalExecutor(TestExecutors.CALLER_THREAD)
            .build();
  }

  @Test
  @DisplayName("read는 문서의 원천 id를 싣는다")
  void readCarriesSourceId() {
    // when
    var page = engine.read(LOCAL, ReadRequest.of("notice"));

    // then
    assertThat(page.sourceId()).isEqualTo("board-a");
  }

  @Test
  @DisplayName("related는 이웃 문서 자신의 원천 id를 싣는다")
  void relatedCarriesNeighborSourceId() {
    // when
    var related = engine.related(LOCAL, RelatedQuery.of("notice"));

    // then
    assertThat(related.documents())
        .extracting(RelatedDocument::documentId, RelatedDocument::sourceId)
        .containsExactly(tuple("patch", "board-b"));
  }

  @Test
  @DisplayName("assemble 항목은 유닛의 원천 id를 싣는다")
  void assembleCarriesSourceId() {
    // when
    var bundle = engine.assemble(LOCAL, AssembleQuery.of("점검"));

    // then
    assertThat(bundle.items())
        .extracting(BundleItem::documentId, BundleItem::sourceId)
        .contains(tuple("notice", "board-a"));
  }
}
