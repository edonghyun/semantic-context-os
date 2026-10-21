package io.scos.engine.internal.search;

import static io.scos.engine.internal.search.SearchFixture.S;
import static io.scos.spi.store.ContextUnits.unit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.type;

import io.scos.engine.DocumentNotFoundException;
import io.scos.engine.internal.search.DocumentReader.Page;
import io.scos.engine.internal.search.DocumentReader.UnitView;
import io.scos.spi.store.SpaceId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** CONTRACTS D1·D3: read_document 페이지 규칙. 25유닛짜리 문서 하나로 검증한다. */
@DisplayName("문서 읽기는 offset·limit 페이지로 나눠 돌려준다(CONTRACTS D1·D3)")
class DocumentReaderTest {
  static final int UNITS = 25;
  SearchFixture fx = new SearchFixture();
  DocumentReader reader;

  @BeforeEach
  void setUp() {
    for (int i = 1; i <= UNITS; i++)
      fx.load(
          S,
          unit("fx-long-" + i)
              .inDocument("fx-long")
              .titled("긴 문서 단락 " + i)
              .content("긴 문서 본문 " + i)
              .build());
    reader = new DocumentReader(fx.units);
  }

  /** offset 0, 기본 limit. */
  Page firstPage() {
    return reader.read(S, "fx-long", 0, DocumentReader.DEFAULT_LIMIT);
  }

  /** offset 20, limit 3. */
  Page midPage() {
    return reader.read(S, "fx-long", 20, 3);
  }

  /** 유닛 25개 문서의 offset 24, limit 5 — 남은 유닛은 1개다. */
  Page lastPage() {
    return reader.read(S, "fx-long", 24, 5);
  }

  @Test
  @DisplayName("첫 페이지는 처음부터 기본 20개다")
  void firstPageDefaultsToTwenty() {
    // when
    Page first = firstPage();

    // then
    assertThat(first.units())
        .hasSize(20)
        .first()
        .isEqualTo(new UnitView("fx-long-1", "긴 문서 단락 1", "긴 문서 본문 1"));
  }

  @Test
  @DisplayName("첫 페이지의 nextOffset은 20이다")
  void firstPageNextOffset() {
    // when
    Page first = firstPage();

    // then
    assertThat(first.nextOffset()).contains(20);
  }

  @Test
  @DisplayName("페이지는 문서 uri를 싣는다")
  void pageCarriesUri() {
    // when
    Page first = firstPage();

    // then
    assertThat(first.uri()).isEqualTo("fixture://fx-long");
  }

  @Test
  @DisplayName("중간 페이지의 제목은 첫 유닛의 문서 제목이다")
  void midPageUsesDocumentTitle() {
    // when
    Page mid = midPage();

    // then
    assertThat(mid.title()).isEqualTo("긴 문서 단락 1");
  }

  @Test
  @DisplayName("중간 페이지는 offset부터 limit개다")
  void midPageIsOffsetPlusLimit() {
    // when
    Page mid = midPage();

    // then
    assertThat(mid.units())
        .extracting(UnitView::title)
        .containsExactly("긴 문서 단락 21", "긴 문서 단락 22", "긴 문서 단락 23");
  }

  @Test
  @DisplayName("중간 페이지의 nextOffset은 offset + limit이다")
  void midPageNextOffset() {
    // when
    Page mid = midPage();

    // then
    assertThat(mid.nextOffset()).contains(23);
  }

  @Test
  @DisplayName("마지막 페이지는 남은 유닛만 담는다")
  void lastPageHoldsRemainder() {
    // when
    Page last = lastPage();

    // then
    assertThat(last.units()).hasSize(1);
  }

  @Test
  @DisplayName("마지막 페이지에는 nextOffset이 없다")
  void lastPageHasNoNextOffset() {
    // when
    Page last = lastPage();

    // then
    assertThat(last.nextOffset()).isEmpty();
  }

  @Test
  @DisplayName("문서 끝을 넘는 offset은 nextOffset 없는 빈 페이지다")
  void beyondEndIsEmptyPage() {
    // when
    Page beyond = reader.read(S, "fx-long", 100, 20);

    // then
    assertThat(beyond.units()).isEmpty();
    assertThat(beyond.nextOffset()).isEmpty();
  }

  @Test
  @DisplayName("끝을 넘은 페이지도 문서 제목과 uri를 싣는다")
  void beyondEndKeepsDocumentHeader() {
    // when
    Page beyond = reader.read(S, "fx-long", 100, 20);

    // then
    assertThat(beyond.title()).isEqualTo("긴 문서 단락 1");
    assertThat(beyond.uri()).isEqualTo("fixture://fx-long");
  }

  @Test
  @DisplayName("없는 문서는 그 id의 DocumentNotFoundException이다")
  void missingDocumentIsNotFound() {
    // when·then
    assertThatThrownBy(() -> reader.read(S, "missing-doc", 0, 20))
        .asInstanceOf(type(DocumentNotFoundException.class))
        .extracting(DocumentNotFoundException::documentId)
        .isEqualTo("missing-doc");
  }

  @Test
  @DisplayName("다른 공간의 문서는 DocumentNotFoundException이다(A2)")
  void otherSpaceDocumentIsNotFound() {
    // given: 자기 공간에서는 읽힌다
    fx.load(
        new SpaceId("other"),
        unit("o-1").inDocument("other-long").titled("다른 공간").content("다른 공간 본문").build());
    Page own = reader.read(new SpaceId("other"), "other-long", 0, 20);
    if (own.units().size() != 1)
      throw new IllegalStateException(
          "given 준비 실패: other 공간에서는 other-long의 유닛 1개가 읽혀야 한다 — " + own.units());

    // when·then
    assertThatThrownBy(() -> reader.read(S, "other-long", 0, 20))
        .asInstanceOf(type(DocumentNotFoundException.class))
        .extracting(DocumentNotFoundException::documentId)
        .isEqualTo("other-long");
  }

  @ParameterizedTest(name = "offset {0}, limit {1}")
  @CsvSource({"-1, 20", "0, 0", "0, 101"})
  @DisplayName("offset 음수·limit 범위 밖(1..100)은 InvalidArgument다")
  void rejectsOutOfRangeArguments(int offset, int limit) {
    // when·then
    assertThatThrownBy(() -> reader.read(S, "fx-long", offset, limit))
        .isInstanceOf(InvalidArgument.class);
  }

  @ParameterizedTest(name = "offset {0}, limit {1}")
  @CsvSource({"0, 1", "0, 100", "24, 1"})
  @DisplayName("offset 0·limit 1·limit 100은 경계 안이다")
  void acceptsBoundaryArguments(int offset, int limit) {
    // when
    Page page = reader.read(S, "fx-long", offset, limit);

    // then
    assertThat(page.units()).isNotEmpty();
  }

  @Test
  @DisplayName("남은 유닛이 limit와 정확히 같으면 nextOffset이 없다")
  void noNextOffsetWhenRemainderFitsExactly() {
    // when
    Page exact = reader.read(S, "fx-long", UNITS - 5, 5);

    // then
    assertThat(exact.nextOffset()).isEmpty();
  }

  @Test
  @DisplayName("limit보다 하나라도 더 남으면 nextOffset은 다음 offset이다")
  void nextOffsetOnlyWhenMoreRemain() {
    // when
    Page shorter = reader.read(S, "fx-long", UNITS - 5, 4);

    // then
    assertThat(shorter.nextOffset()).contains(24);
  }
}
