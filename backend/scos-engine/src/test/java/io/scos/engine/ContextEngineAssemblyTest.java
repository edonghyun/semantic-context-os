package io.scos.engine;

import static io.scos.engine.ContextEngineAssemblyScenario.ALICE;
import static io.scos.engine.ContextEngineAssemblyScenario.DOCUMENT;
import static io.scos.engine.ContextEngineAssemblyScenario.UNKNOWN;
import static io.scos.engine.internal.search.SearchFixture.S;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import io.scos.engine.internal.search.QuestionSearch;
import io.scos.spi.access.AccessKey;
import io.scos.spi.embedding.FixedEmbedder;
import io.scos.spi.store.AccessScope;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("엔진은 명시한 접근 모드로 요청 범위를 정한다(설계 §4.5, A2)")
class ContextEngineAssemblyTest {
  @Test
  @DisplayName("벡터 포트 없는 키워드 전용 엔진도 문서를 검색한다")
  void keywordOnlySearchNeedsNoVectorPort() {
    // given
    var builder = new ContextEngineAssemblyScenario().keywordOnlyWithoutVectors();
    // when
    var result = builder.build().search(ALICE, SearchQuery.of("정산"));
    // then
    assertThat(result.hits())
        .extracting(ContextHit::documentId)
        .containsExactlyInAnyOrder("settlement", "related");
  }

  @Test
  @DisplayName("벡터 포트 없는 키워드 전용 엔진은 링크로 관련 문서를 찾는다")
  void keywordOnlyRelatedNeedsNoVectorPort() {
    // given
    var builder = new ContextEngineAssemblyScenario().keywordOnlyWithoutVectors();
    // when
    var result = builder.build().related(ALICE, RelatedQuery.of(DOCUMENT));
    // then
    assertThat(result.documents())
        .extracting(RelatedDocument::documentId, RelatedDocument::via)
        .containsExactly(tuple("related", RelatedDocument.Via.LINK));
  }

  @Test
  @DisplayName("임베더를 선택하면 벡터 포트 누락을 거부한다")
  void embeddedSearchRequiresVectorPort() {
    // given
    var builder =
        new ContextEngineAssemblyScenario()
            .keywordOnlyWithoutVectors()
            .embedder(FixedEmbedder.zero(2));
    // when·then
    assertThatThrownBy(builder::build)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("vectors is required");
  }

  @Test
  @DisplayName("access 엔진에서 미등록 principal의 read는 문서 없음이다")
  void unknownPrincipalCannotRead() {
    // given
    var engine = new ContextEngineAssemblyScenario().restricted();

    // when·then
    assertThatThrownBy(() -> engine.read(UNKNOWN, ReadRequest.of(DOCUMENT)))
        .isInstanceOfSatisfying(
            DocumentNotFoundException.class,
            e -> assertThat(e.documentId()).isEqualTo("settlement"));
  }

  @Test
  @DisplayName("access 엔진에서 미등록 principal의 related는 문서 없음이다")
  void unknownPrincipalCannotReadRelated() {
    // given
    var engine = new ContextEngineAssemblyScenario().restricted();

    // when·then
    assertThatThrownBy(() -> engine.related(UNKNOWN, RelatedQuery.of(DOCUMENT)))
        .isInstanceOfSatisfying(
            DocumentNotFoundException.class,
            e -> assertThat(e.documentId()).isEqualTo("settlement"));
  }

  @Test
  @DisplayName("access 엔진에서 미등록 principal의 assemble은 항목이 없다")
  void unknownPrincipalCannotAssemble() {
    // given
    var engine = new ContextEngineAssemblyScenario().restricted();

    // when
    var bundle = engine.assemble(UNKNOWN, AssembleQuery.of("정산"));

    // then
    assertThat(bundle.items()).isEmpty();
  }

  @Test
  @DisplayName("access 엔진의 최신성은 요청자의 원천 수집 시각이다")
  void restrictedFreshnessUsesSourceHistory() {
    // given
    var engine = new ContextEngineAssemblyScenario().restricted();

    // when
    var freshness = engine.freshness(ALICE, S);

    // then
    assertThat(freshness.latestCapturedAt()).contains("2026-10-13T00:00:00Z");
  }

  @Test
  @DisplayName("access 엔진에서 미등록 principal의 search는 결과가 없다")
  void unknownPrincipalCannotSearch() {
    // given
    var engine = new ContextEngineAssemblyScenario().restricted();

    // when
    var result = engine.search(UNKNOWN, SearchQuery.of("정산"));

    // then
    assertThat(result.hits()).isEmpty();
  }

  @Test
  @DisplayName("access 엔진에서 미등록 principal의 freshness는 확인 불가다")
  void unknownPrincipalHasNoFreshness() {
    // given
    var engine = new ContextEngineAssemblyScenario().restricted();

    // when
    var result = engine.freshness(UNKNOWN, S);

    // then
    assertThat(result).isEqualTo(Freshness.UNAVAILABLE);
  }

  @Test
  @DisplayName("같은 코퍼스의 unrestricted search는 결과가 있다")
  void unrestrictedSearchHasData() {
    // given
    var engine = new ContextEngineAssemblyScenario().unrestricted();

    // when
    var result = engine.search(ALICE, SearchQuery.of("정산"));

    // then
    assertThat(result.hits()).isNotEmpty();
  }

  @Test
  @DisplayName("같은 코퍼스의 unrestricted read는 유닛이 있다")
  void unrestrictedReadHasData() {
    // given
    var engine = new ContextEngineAssemblyScenario().unrestricted();

    // when
    var result = engine.read(ALICE, ReadRequest.of(DOCUMENT));

    // then
    assertThat(result.units()).isNotEmpty();
  }

  @Test
  @DisplayName("같은 코퍼스의 unrestricted related는 문서가 있다")
  void unrestrictedRelatedHasData() {
    // given
    var engine = new ContextEngineAssemblyScenario().unrestricted();

    // when
    var result = engine.related(ALICE, RelatedQuery.of(DOCUMENT));

    // then
    assertThat(result.documents()).isNotEmpty();
  }

  @Test
  @DisplayName("같은 코퍼스의 unrestricted assemble은 항목이 있다")
  void unrestrictedAssembleHasData() {
    // given
    var engine = new ContextEngineAssemblyScenario().unrestricted();

    // when
    var result = engine.assemble(ALICE, AssembleQuery.of("정산"));

    // then
    assertThat(result.items()).isNotEmpty();
  }

  @Test
  @DisplayName("같은 코퍼스의 unrestricted freshness는 시각이 있다")
  void unrestrictedFreshnessHasData() {
    // given
    var engine = new ContextEngineAssemblyScenario().unrestricted();

    // when
    var result = engine.freshness(ALICE, S);

    // then
    assertThat(result.latestCapturedAt()).isPresent();
  }

  @Test
  @DisplayName("같은 엔진도 다음 요청에는 변경된 사용자 키를 읽는다")
  void eachRequestResolvesCurrentKeys() {
    // given
    var scenario = new ContextEngineAssemblyScenario();
    var engine = scenario.restricted();
    scenario.deactivateAliceAfterRequest(engine);

    // when
    var result = engine.searchRecipe(ALICE, SearchQuery.of("정산"));

    // then
    assertThat(result.access()).isEqualTo(new AccessScope.Restricted(ALICE, Set.of()));
  }

  @Test
  @DisplayName("unrestricted 엔진의 모드는 UNRESTRICTED다")
  void unrestrictedModeIsReported() {
    // given
    var scenario = new ContextEngineAssemblyScenario();

    // when
    var mode = scenario.unrestricted().accessMode();

    // then
    assertThat(mode).isEqualTo(AccessMode.UNRESTRICTED);
  }

  @Test
  @DisplayName("access 엔진의 모드는 RESTRICTED다")
  void restrictedModeIsReported() {
    // given
    var scenario = new ContextEngineAssemblyScenario();

    // when
    var mode = scenario.restricted().accessMode();

    // then
    assertThat(mode).isEqualTo(AccessMode.RESTRICTED);
  }

  @Test
  @DisplayName("빈 빌더도 포트보다 접근 모드 누락을 먼저 알린다")
  void missingModePrecedesMissingPorts() {
    // given
    var builder = ContextEngine.builder();

    // when·then
    assertThatThrownBy(builder::build)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("access mode is required: call unrestricted() or access(...)");
  }

  @Test
  @DisplayName("모드를 정하지 않으면 build가 실패한다")
  void missingAccessModeIsRejected() {
    // given
    var builder = new ContextEngineAssemblyScenario().withoutMode();

    // when·then
    assertThatThrownBy(builder::build)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("access mode is required: call unrestricted() or access(...)");
  }

  @ParameterizedTest
  @CsvSource({
    "UNRESTRICTED, UNRESTRICTED",
    "UNRESTRICTED, RESTRICTED",
    "RESTRICTED, UNRESTRICTED",
    "RESTRICTED, RESTRICTED"
  })
  @DisplayName("접근 모드를 두 번 정하면 조합과 관계없이 거부한다")
  void duplicateAccessModeIsRejected(AccessMode first, AccessMode second) {
    // given
    var scenario = new ContextEngineAssemblyScenario();
    var builder = scenario.builder(first);

    // when·then
    assertThatThrownBy(() -> scenario.selectMode(builder, second).build())
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("access mode set twice");
  }

  @Test
  @DisplayName("access 엔진은 요청 principal의 키로 검색 범위를 만든다")
  void principalReachesSearchRecipe() {
    // given
    var scenario = new ContextEngineAssemblyScenario();
    DefaultContextEngine engine = scenario.restricted();

    // when
    QuestionSearch.Result result = engine.searchRecipe(ALICE, SearchQuery.of("정산"));

    // then
    assertThat(result.access())
        .isEqualTo(
            new AccessScope.Restricted(
                ALICE, Set.of(AccessKey.parse("domain:drive-a:example.com"))));
  }
}
