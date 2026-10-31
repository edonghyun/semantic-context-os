package io.scos.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.engine.AccessPropertyScenario.Requester;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("무작위 접근 세계에서도 요청자 범위를 넘는 문서는 나오지 않는다")
class AccessPropertyTest {
  // 세 독립 세계를 고정하여 반례를 같은 시드로 재현한다.
  private static final long FIRST_SEED = 20262326L;
  private static final long SECOND_SEED = 20264316L;
  private static final long THIRD_SEED = 20266243L;

  @ParameterizedTest(name = "seed={0}")
  @MethodSource("seeds")
  @DisplayName("각 시드에는 그룹 키로만 보이는 문서가 있다")
  void worldHasGroupOnlyVisibility(long seed) {
    // given
    var coverage = new AccessWorldCoverage(RandomAccessWorld.generate(seed));

    // when
    var witnesses = coverage.groupOnlyPairs();

    // then
    assertThat(witnesses).as("seed=%s", seed).isNotEmpty();
  }

  @ParameterizedTest(name = "seed={0}")
  @MethodSource("seeds")
  @DisplayName("각 시드에는 일반 그룹의 합성 그룹 펼침으로만 보이는 문서가 있다")
  void worldHasExpandedOnlyVisibility(long seed) {
    // given
    var coverage = new AccessWorldCoverage(RandomAccessWorld.generate(seed));

    // when
    var witnesses = coverage.expandedOnlyPairs();

    // then
    assertThat(witnesses).as("seed=%s", seed).isNotEmpty();
  }

  @ParameterizedTest(name = "seed={0}")
  @MethodSource("seeds")
  @DisplayName("각 시드에는 같은 질의에 보이는 문서와 숨은 문서가 경쟁한다")
  void worldHasCompetingVisibility(long seed) {
    // given
    var coverage = new AccessWorldCoverage(RandomAccessWorld.generate(seed));

    // when
    var witnesses = coverage.competingQueries();

    // then
    assertThat(witnesses).as("seed=%s", seed).isNotEmpty();
  }

  @ParameterizedTest(name = "{0}, query={1}")
  @MethodSource("queries")
  @DisplayName("검색의 모든 문서는 독립 가시 집합 안에 있다")
  void searchStaysVisible(Requester requester, String query) {
    // given
    var scenario = requester.scenario();
    // when
    var result = scenario.restricted().search(requester.principal(), SearchQuery.of(query));
    // then
    assertThat(result.hits())
        .allSatisfy(
            hit ->
                assertThat(requester.visibleDocuments())
                    .as(() -> requester.counterexample(hit.documentId()))
                    .contains(hit.documentId()));
  }

  @ParameterizedTest(name = "{0}, query={1}")
  @MethodSource("queries")
  @DisplayName("검색 결과와 점수는 독립 가시 코퍼스의 값과 같다")
  void searchMatchesVisibleCorpus(Requester requester, String query) {
    // given
    var scenario = requester.scenario();
    var expected = scenario.visibleOnly().search(requester.principal(), SearchQuery.of(query));
    // when
    var result = scenario.restricted().search(requester.principal(), SearchQuery.of(query));
    // then
    assertThat(result.hits()).as("%s, query=%s", requester, query).isEqualTo(expected.hits());
  }

  @ParameterizedTest(name = "{0}, query={1}")
  @MethodSource("queries")
  @DisplayName("조립의 모든 문서는 독립 가시 집합 안에 있다")
  void assemblyStaysVisible(Requester requester, String query) {
    // given
    var scenario = requester.scenario();
    // when
    var result = scenario.restricted().assemble(requester.principal(), AssembleQuery.of(query));
    // then
    assertThat(result.items())
        .allSatisfy(
            item ->
                assertThat(requester.visibleDocuments())
                    .as(() -> requester.counterexample(item.documentId()))
                    .contains(item.documentId()));
  }

  @ParameterizedTest(name = "{0}, document={1}")
  @MethodSource("visibleDocuments")
  @DisplayName("관련 문서의 모든 대상은 독립 가시 집합 안에 있다")
  void relatedStaysVisible(Requester requester, String document) {
    // given
    var scenario = requester.scenario();
    // when
    var result = scenario.restricted().related(requester.principal(), RelatedQuery.of(document));
    // then
    assertThat(result.documents())
        .allSatisfy(
            target ->
                assertThat(requester.visibleDocuments())
                    .as(() -> requester.counterexample(target.documentId()))
                    .contains(target.documentId()));
  }

  @ParameterizedTest(name = "{0}, document={1}")
  @MethodSource("visibleDocuments")
  @DisplayName("보이는 문서는 그 문서에 속한 원본을 읽는다")
  void readStaysVisible(Requester requester, String document) {
    // given
    var scenario = requester.scenario();
    // when
    var result = scenario.restricted().read(requester.principal(), ReadRequest.of(document));
    // then
    assertThat(result.units())
        .as(() -> requester.counterexample(document))
        .extracting(UnitView::unitId)
        .containsExactly("unit-" + document);
  }

  @ParameterizedTest(name = "{0}, document={1}")
  @MethodSource("blockedDocuments")
  @DisplayName("가시 집합 밖 문서를 읽으면 문서 부재 오류다")
  void blockedReadIsAbsent(Requester requester, String document) {
    // given
    var scenario = requester.scenario();
    // when·then
    assertThatThrownBy(
            () -> scenario.restricted().read(requester.principal(), ReadRequest.of(document)))
        .as(() -> requester.counterexample(document))
        .isExactlyInstanceOf(DocumentNotFoundException.class)
        .hasMessage(new DocumentNotFoundException(document).getMessage());
  }

  @ParameterizedTest(name = "{0}, document={1}")
  @MethodSource("blockedDocuments")
  @DisplayName("가시 집합 밖 관련 시드는 문서 부재 오류다")
  void blockedRelatedIsAbsent(Requester requester, String document) {
    // given
    var scenario = requester.scenario();
    // when·then
    assertThatThrownBy(
            () -> scenario.restricted().related(requester.principal(), RelatedQuery.of(document)))
        .as(() -> requester.counterexample(document))
        .isExactlyInstanceOf(DocumentNotFoundException.class)
        .hasMessage(new DocumentNotFoundException(document).getMessage());
  }

  private static Stream<Requester> requesters() {
    return seeds()
        .mapToObj(RandomAccessWorld::generate)
        .flatMap(
            world -> world.directory().users().stream().map(user -> new Requester(world, user)));
  }

  private static LongStream seeds() {
    return LongStream.of(FIRST_SEED, SECOND_SEED, THIRD_SEED);
  }

  private static Stream<Arguments> blockedDocuments() {
    return requesters()
        .flatMap(
            requester ->
                requester.world().corpus().stream()
                    .map(unit -> unit.documentId())
                    .filter(document -> !requester.visibleDocuments().contains(document))
                    .map(document -> Arguments.of(requester, document)));
  }

  private static Stream<Arguments> visibleDocuments() {
    return requesters()
        .flatMap(
            requester ->
                requester.visibleDocuments().stream()
                    .sorted()
                    .map(document -> Arguments.of(requester, document)));
  }

  private static Stream<Arguments> queries() {
    return requesters()
        .flatMap(
            requester ->
                RandomAccessWorld.QUERIES.stream().map(query -> Arguments.of(requester, query)));
  }
}
